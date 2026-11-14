package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.SseOutbox;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmEvent;
import io.github.kalpak44.workspacedai.vmmanager.vm.VmService;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDtoMapper;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans each {@link VmEvent} out to every open event stream. A change only queues on each client's
 * own {@link SseOutbox}, so a client that stops reading never holds up the request that made the
 * change, or any other client.
 */
@Component
public class VmEventStream {

  static final String STATUS = "status";
  static final String DELETED = "deleted";

  private final Map<SseEmitter, SseOutbox> subscribers = new ConcurrentHashMap<>();
  private final VmService service;
  private final VmDtoMapper mapper;

  public VmEventStream(VmService service, VmDtoMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  /**
   * Registers before it queues the snapshot, so a change landing in between arrives twice rather
   * than not at all; a repeated status is harmless to the client.
   */
  public SseEmitter subscribe() {
    // 0 disables the async timeout: the stream lives as long as the client keeps it open.
    var emitter = new SseEmitter(0L);
    var outbox = new SseOutbox(emitter, "vm-events");
    emitter.onCompletion(() -> forget(emitter));
    emitter.onTimeout(() -> forget(emitter));
    emitter.onError(_ -> forget(emitter));
    subscribers.put(emitter, outbox);
    for (var vm : service.list(Optional.empty())) {
      outbox.offer(SseEmitter.event().name(STATUS).data(mapper.toDto(vm)));
    }
    return emitter;
  }

  @EventListener
  void on(VmEvent.Changed event) {
    sendToAll(() -> SseEmitter.event().name(STATUS).data(mapper.toDto(event.vm())));
  }

  @EventListener
  void on(VmEvent.Deleted event) {
    sendToAll(() -> SseEmitter.event().name(DELETED).data(Map.of("id", event.id())));
  }

  /** A comment line every half minute, so a proxy never sees the stream as idle. */
  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void keepalive() {
    sendToAll(() -> SseEmitter.event().comment("keepalive"));
  }

  /**
   * Ends every stream as soon as shutdown begins. An open stream is an active request, and graceful
   * shutdown would otherwise wait out its whole timeout on each one; the client reconnects to the
   * next pod by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    subscribers.keySet().forEach(SseEmitter::complete);
    subscribers.keySet().forEach(this::forget);
  }

  /** A builder per client: building one appends to it, so it cannot be shared. */
  private void sendToAll(Supplier<SseEmitter.SseEventBuilder> event) {
    subscribers.values().forEach(outbox -> outbox.offer(event.get()));
  }

  private void forget(SseEmitter emitter) {
    var outbox = subscribers.remove(emitter);
    if (outbox != null) {
      outbox.close();
    }
  }
}
