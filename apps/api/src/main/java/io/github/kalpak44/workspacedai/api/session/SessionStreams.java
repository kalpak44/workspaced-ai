package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.SseOutbox;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEvent;
import io.github.kalpak44.workspacedai.api.harness.HarnessEventEnvelope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Relays one OpenCode V2 event connection per workspace to every browser watching its sessions. The
 * connection's reader only queues each event on every browser's own {@link SseOutbox}, so a browser
 * that stops reading cannot hold up the others, and an event that fails to relay is dropped rather
 * than ending the connection for all of them.
 */
@Component
public class SessionStreams {

  private static final Logger LOG = LoggerFactory.getLogger(SessionStreams.class);

  private final Harness harness;
  private final WorkspaceSessionRepository sessions;
  private final Map<UUID, Hub> hubs = new HashMap<>();
  private final Map<SseEmitter, Subscriber> subscribers = new HashMap<>();

  public SessionStreams(Harness harness, WorkspaceSessionRepository sessions) {
    this.harness = harness;
    this.sessions = sessions;
  }

  public synchronized SseEmitter subscribe(SessionService.Target target) {
    var emitter = new SseEmitter(0L);
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      emitter.completeWithError(e);
      return emitter;
    }
    var subscriber =
        new Subscriber(target, emitter, new SseOutbox(emitter, "session-events-" + target.id()));
    subscribers.put(emitter, subscriber);
    var hub = hubs.computeIfAbsent(target.workspaceId(), ignored -> new Hub());
    hub.add(subscriber);
    if (hub.upstream == null) {
      try {
        hub.upstream =
            harness.stream(
                target.endpoint(),
                envelope -> relay(target.workspaceId(), envelope),
                () -> ended(target.workspaceId(), hub));
      } catch (RuntimeException e) {
        hub.remove(subscriber);
        subscribers.remove(emitter);
        if (hub.empty()) {
          hubs.remove(target.workspaceId());
        }
        subscriber.outbox.close();
        emitter.completeWithError(e);
        return emitter;
      }
    }
    emitter.onCompletion(() -> close(emitter));
    emitter.onTimeout(() -> close(emitter));
    emitter.onError(e -> close(emitter));
    return emitter;
  }

  private void relay(UUID workspaceId, HarnessEventEnvelope envelope) {
    ArrayList<Subscriber> recipients;
    synchronized (this) {
      var hub = hubs.get(workspaceId);
      if (hub == null) {
        return;
      }
      recipients = hub.recipients(envelope.sessionId());
    }
    var event = envelope.event();
    try {
      if (event instanceof HarnessEvent.Title(var title)) {
        // Once per session, however many browsers are watching it.
        recipients.stream()
            .map(subscriber -> subscriber.target.id())
            .distinct()
            .forEach(id -> sessions.retitle(id, title));
      }
      // A builder per browser: building one appends to it, so it cannot be shared.
      recipients.forEach(subscriber -> subscriber.outbox.offer(sse(event)));
    } catch (RuntimeException e) {
      LOG.warn("dropped a {} event for workspace {}", event.name(), workspaceId, e);
    }
  }

  private static SseEmitter.SseEventBuilder sse(HarnessEvent event) {
    return event instanceof HarnessEvent.Heartbeat
        ? SseEmitter.event().comment("keepalive")
        : SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON);
  }

  private synchronized void close(SseEmitter emitter) {
    var subscriber = subscribers.remove(emitter);
    if (subscriber == null) {
      return;
    }
    subscriber.outbox.close();
    var hub = hubs.get(subscriber.target.workspaceId());
    if (hub == null) {
      return;
    }
    hub.remove(subscriber);
    if (hub.empty()) {
      hubs.remove(subscriber.target.workspaceId());
      close(hub.upstream);
    }
  }

  private void ended(UUID workspaceId, Hub expected) {
    ArrayList<Subscriber> ended;
    synchronized (this) {
      if (hubs.get(workspaceId) != expected) {
        return;
      }
      hubs.remove(workspaceId);
      ended = expected.all();
      ended.forEach(s -> subscribers.remove(s.emitter));
    }
    ended.forEach(s -> s.outbox.complete());
  }

  private static void close(AutoCloseable upstream) {
    if (upstream == null) {
      return;
    }
    try {
      upstream.close();
    } catch (Exception _) {
      // Already closed; nothing is left to release.
    }
  }

  @EventListener(ContextClosedEvent.class)
  void closeAll() {
    ArrayList<Hub> closing;
    synchronized (this) {
      closing = new ArrayList<>(hubs.values());
      hubs.clear();
      subscribers.clear();
    }
    closing.forEach(
        hub -> {
          close(hub.upstream);
          hub.all()
              .forEach(
                  s -> {
                    s.outbox.close();
                    s.emitter.complete();
                  });
        });
  }

  private record Subscriber(SessionService.Target target, SseEmitter emitter, SseOutbox outbox) {}

  private static final class Hub {
    private final Map<String, Set<Subscriber>> sessions = new HashMap<>();
    private AutoCloseable upstream;

    private void add(Subscriber subscriber) {
      sessions
          .computeIfAbsent(subscriber.target.harnessSessionId(), ignored -> new HashSet<>())
          .add(subscriber);
    }

    private void remove(Subscriber subscriber) {
      var own = sessions.get(subscriber.target.harnessSessionId());
      if (own != null) {
        own.remove(subscriber);
        if (own.isEmpty()) {
          sessions.remove(subscriber.target.harnessSessionId());
        }
      }
    }

    private ArrayList<Subscriber> recipients(String sessionId) {
      return sessionId == null
          ? all()
          : new ArrayList<>(sessions.getOrDefault(sessionId, Set.of()));
    }

    private ArrayList<Subscriber> all() {
      var all = new ArrayList<Subscriber>();
      sessions.values().forEach(all::addAll);
      return all;
    }

    private boolean empty() {
      return sessions.isEmpty();
    }
  }
}
