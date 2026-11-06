package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fans each workspace status change out to the open event streams of the user who owns it. */
@Component
public class WorkspaceEvents {

  static final String STATUS = "status";
  static final String DELETED = "deleted";

  // Each open stream, and the user it was opened by.
  private final Map<SseEmitter, UUID> emitters = new ConcurrentHashMap<>();
  private final WorkspaceService service;
  private final ApplicationEventPublisher events;

  public WorkspaceEvents(
      WorkspaceService service, WorkspaceRuntime runtime, ApplicationEventPublisher events) {
    this.service = service;
    this.events = events;
    runtime.watch(this::publish);
  }

  /**
   * Registers before it sends the snapshot, so a change landing in between arrives twice rather
   * than not at all; a repeated status is harmless to the client.
   */
  public SseEmitter subscribe(UUID ownerId) {
    // 0 disables the async timeout: the stream lives as long as the client keeps it open.
    var emitter = new SseEmitter(0L);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    emitters.put(emitter, ownerId);
    try {
      for (var workspace : service.list(ownerId)) {
        emitter.send(event(workspace.id(), workspace.status()));
      }
    } catch (IOException _) {
      drop(emitter);
    }
    return emitter;
  }

  /**
   * To the owner's browsers, and to whatever in the API acts on a workspace becoming ready. A
   * workspace already gone has no owner left to tell; its deleted event has covered that.
   */
  void publish(UUID id, WorkspaceStatus status) {
    service.ownerOf(id).ifPresent(owner -> sendTo(owner, event(id, status)));
    events.publishEvent(new WorkspaceStatusChanged(id, status));
  }

  /**
   * Tells the owner's clients the workspace is gone, once its delete has committed. Without it,
   * another open tab would only see the pod disappear and show the workspace as failed.
   */
  @TransactionalEventListener
  void onDeleted(WorkspaceDeleted deleted) {
    sendTo(deleted.ownerId(), SseEmitter.event().name(DELETED).data(Map.of("id", deleted.id())));
  }

  /** A comment line every half minute, so a proxy never sees the stream as idle. */
  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void heartbeat() {
    broadcast(SseEmitter.event().comment("keepalive"));
  }

  private void sendTo(UUID ownerId, SseEmitter.SseEventBuilder event) {
    emitters.forEach(
        (emitter, owner) -> {
          if (owner.equals(ownerId)) {
            send(emitter, event);
          }
        });
  }

  private void broadcast(SseEmitter.SseEventBuilder event) {
    emitters.keySet().forEach(emitter -> send(emitter, event));
  }

  private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException _) {
      drop(emitter);
    }
  }

  /**
   * Ends every stream as soon as shutdown begins. An open stream is an active request, and graceful
   * shutdown would otherwise wait out its whole timeout on each one; the browser's EventSource
   * reconnects to the next pod by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    emitters.keySet().forEach(this::drop);
  }

  private void drop(SseEmitter emitter) {
    emitters.remove(emitter);
    emitter.complete();
  }

  private static SseEmitter.SseEventBuilder event(UUID id, WorkspaceStatus status) {
    return SseEmitter.event().name(STATUS).data(Map.of("id", id, STATUS, status));
  }
}
