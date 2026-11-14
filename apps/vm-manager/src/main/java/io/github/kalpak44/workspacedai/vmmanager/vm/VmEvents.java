package io.github.kalpak44.workspacedai.vmmanager.vm;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sends every VM change to each open event stream; the workspace API is the one listening. */
@Component
public class VmEvents {

  static final String STATUS = "status";
  static final String DELETED = "deleted";

  private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

  /** A stream that stays open until the client closes it. */
  public SseEmitter subscribe(Iterable<Vm> snapshot) {
    var emitter = new SseEmitter(0L);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(_ -> emitters.remove(emitter));
    emitters.add(emitter);
    for (var vm : snapshot) {
      send(emitter, statusEvent(vm));
    }
    return emitter;
  }

  void publishStatus(Vm vm) {
    emitters.forEach(emitter -> send(emitter, statusEvent(vm)));
  }

  void publishDeleted(UUID id) {
    emitters.forEach(
        emitter -> send(emitter, SseEmitter.event().name(DELETED).data(Map.of("id", id))));
  }

  /** A comment line every half minute, so a proxy never sees the stream as idle. */
  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void keepalive() {
    emitters.forEach(emitter -> send(emitter, SseEmitter.event().comment("keepalive")));
  }

  /**
   * Ends every open stream when the app shuts down, which would otherwise wait for them; a client
   * reconnects to the next instance by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    emitters.forEach(SseEmitter::complete);
    emitters.clear();
  }

  private static SseEmitter.SseEventBuilder statusEvent(Vm vm) {
    return SseEmitter.event().name(STATUS).data(vm);
  }

  private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException _) {
      emitters.remove(emitter);
    }
  }
}
