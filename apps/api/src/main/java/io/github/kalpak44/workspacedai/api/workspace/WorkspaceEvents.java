package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fans each workspace status change out to every open event stream. */
@Component
public class WorkspaceEvents {

  static final String STATUS = "status";
  static final String DELETED = "deleted";

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
  private final WorkspaceService service;

  public WorkspaceEvents(WorkspaceService service, WorkspaceRuntime runtime) {
    this.service = service;
    runtime.watch(this::publish);
  }

  /**
   * Registers before it sends the snapshot, so a change landing in between arrives twice rather
   * than not at all; a repeated status is harmless to the client.
   */
  public SseEmitter subscribe() {
    // 0 disables the async timeout: the stream lives as long as the client keeps it open.
    var emitter = new SseEmitter(0L);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    emitters.add(emitter);
    try {
      for (var workspace : service.list()) {
        emitter.send(event(workspace.id(), workspace.status()));
      }
    } catch (IOException _) {
      drop(emitter);
    }
    return emitter;
  }

  void publish(UUID id, WorkspaceStatus status) {
    broadcast(event(id, status));
  }

  /**
   * Tells every client the workspace is gone, once its delete has committed. Without it, another
   * open tab would only see the pod disappear and show the workspace as failed.
   */
  @TransactionalEventListener
  void onDeleted(WorkspaceDeleted deleted) {
    broadcast(SseEmitter.event().name(DELETED).data(Map.of("id", deleted.id())));
  }

  /** A comment line every half minute, so a proxy never sees the stream as idle. */
  @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
  void heartbeat() {
    broadcast(SseEmitter.event().comment("keepalive"));
  }

  private void broadcast(SseEmitter.SseEventBuilder event) {
    for (var emitter : emitters) {
      try {
        emitter.send(event);
      } catch (IOException _) {
        drop(emitter);
      }
    }
  }

  /**
   * Ends every stream as soon as shutdown begins. An open stream is an active request, and graceful
   * shutdown would otherwise wait out its whole timeout on each one; the browser's EventSource
   * reconnects to the next pod by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    emitters.forEach(this::drop);
  }

  private void drop(SseEmitter emitter) {
    emitters.remove(emitter);
    emitter.complete();
  }

  private static SseEmitter.SseEventBuilder event(UUID id, WorkspaceStatus status) {
    return SseEmitter.event().name(STATUS).data(Map.of("id", id, STATUS, status));
  }
}
