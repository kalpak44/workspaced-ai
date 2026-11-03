package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Relays one session's harness events to one browser. Each open chat holds its own connection to
 * the harness, closed as soon as the browser's is.
 */
@Component
public class SessionStreams {

  private final Harness harness;
  private final WorkspaceSessionRepository sessions;
  private final Map<SseEmitter, AutoCloseable> open = new ConcurrentHashMap<>();

  public SessionStreams(Harness harness, WorkspaceSessionRepository sessions) {
    this.harness = harness;
    this.sessions = sessions;
  }

  public SseEmitter subscribe(SessionService.Target target) {
    // 0 disables the async timeout: the stream lives as long as the browser keeps it open.
    var emitter = new SseEmitter(0L);
    var upstream =
        harness.stream(
            target.endpoint(),
            target.path(),
            target.harnessSessionId(),
            event -> relay(emitter, target, event),
            emitter::complete);
    open.put(emitter, upstream);
    emitter.onCompletion(() -> close(emitter));
    emitter.onTimeout(() -> close(emitter));
    emitter.onError(e -> close(emitter));
    return emitter;
  }

  private void relay(SseEmitter emitter, SessionService.Target target, HarnessEvent event) {
    try {
      switch (event) {
        // The harness's own heartbeat is what reveals a browser that has gone: the send fails.
        case HarnessEvent.Heartbeat() -> emitter.send(SseEmitter.event().comment("keepalive"));
        case HarnessEvent.Title(var title) -> {
          sessions.retitle(target.id(), title);
          send(emitter, event);
        }
        default -> send(emitter, event);
      }
    } catch (IOException | IllegalStateException e) {
      // The browser went away mid-send; completing ends the upstream connection too.
      emitter.completeWithError(e);
    }
  }

  private static void send(SseEmitter emitter, HarnessEvent event) throws IOException {
    emitter.send(SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON));
  }

  private void close(SseEmitter emitter) {
    var upstream = open.remove(emitter);
    if (upstream != null) {
      try {
        upstream.close();
      } catch (Exception _) {
        // Already closed; nothing is left to release.
      }
    }
  }

  /**
   * Ends every stream as soon as shutdown begins, as WorkspaceEvents does; the browser's
   * EventSource reconnects to the next pod by itself.
   */
  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    open.keySet()
        .forEach(
            emitter -> {
              close(emitter);
              emitter.complete();
            });
  }
}
