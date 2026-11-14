package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.SseOutbox;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans each workspace status change out to the open event streams of the user who owns it. Changes
 * arrive on the runtime's own thread, which only queues them on each browser's own {@link
 * SseOutbox}: a browser that stops reading must not hold up that thread, or every other browser.
 */
@Component
public class WorkspaceEvents {

  static final String STATUS = "status";
  static final String DELETED = "deleted";

  // Each open stream, with the user it was opened by and what it has still to be sent.
  private final Map<SseEmitter, Subscriber> subscribers = new ConcurrentHashMap<>();
  private final WorkspaceService service;
  private final ApplicationEventPublisher events;

  private record Subscriber(UUID ownerId, SseOutbox outbox) {}

  public WorkspaceEvents(
      WorkspaceService service, WorkspaceRuntime runtime, ApplicationEventPublisher events) {
    this.service = service;
    this.events = events;
    runtime.watch(this::publish);
  }

  /**
   * Registers before it queues the snapshot, so a change landing in between arrives twice rather
   * than not at all; a repeated status is harmless to the client.
   */
  public SseEmitter subscribe(UUID ownerId) {
    // 0 disables the async timeout: the stream lives as long as the client keeps it open.
    var emitter = new SseEmitter(0L);
    var outbox = new SseOutbox(emitter, "workspace-events");
    emitter.onCompletion(() -> forget(emitter));
    emitter.onTimeout(() -> forget(emitter));
    emitter.onError(_ -> forget(emitter));
    subscribers.put(emitter, new Subscriber(ownerId, outbox));
    for (var workspace : service.list(ownerId)) {
      outbox.offer(event(workspace.id(), workspace.status()));
    }
    return emitter;
  }

  /**
   * To the owner's browsers, and to whatever in the API acts on a workspace becoming ready. A
   * workspace already gone has no owner left to tell; its deleted event has covered that.
   */
  void publish(UUID id, WorkspaceStatus status) {
    service.ownerOf(id).ifPresent(owner -> sendTo(owner, () -> event(id, status)));
    events.publishEvent(new WorkspaceStatusChanged(id, status));
  }

  /**
   * Tells the owner's clients on this instance the workspace is gone. {@link WorkspaceDeletions}
   * calls it on every instance once the delete has committed, whichever one made it. Without it,
   * another open tab would only see the VM disappear and show the workspace as failed.
   */
  void publishDeleted(UUID id, UUID ownerId) {
    sendTo(ownerId, () -> SseEmitter.event().name(DELETED).data(Map.of("id", id)));
  }

  /** A comment line every half minute, so a proxy never sees the stream as idle. */
  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void heartbeat() {
    subscribers.values().forEach(s -> s.outbox().offer(SseEmitter.event().comment("keepalive")));
  }

  /** A builder per browser: building one appends to it, so it cannot be shared. */
  private void sendTo(UUID ownerId, Supplier<SseEmitter.SseEventBuilder> event) {
    subscribers.values().stream()
        .filter(s -> s.ownerId().equals(ownerId))
        .forEach(s -> s.outbox().offer(event.get()));
  }

  /**
   * Ends every stream as soon as shutdown begins. An open stream is an active request, and graceful
   * shutdown would otherwise wait out its whole timeout on each one; the browser's EventSource
   * reconnects to the next API instance by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    subscribers.keySet().forEach(SseEmitter::complete);
    subscribers.keySet().forEach(this::forget);
  }

  private void forget(SseEmitter emitter) {
    var subscriber = subscribers.remove(emitter);
    if (subscriber != null) {
      subscriber.outbox().close();
    }
  }

  private static SseEmitter.SseEventBuilder event(UUID id, WorkspaceStatus status) {
    return SseEmitter.event().name(STATUS).data(Map.of("id", id, STATUS, status));
  }
}
