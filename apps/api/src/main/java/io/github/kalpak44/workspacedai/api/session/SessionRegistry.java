package io.github.kalpak44.workspacedai.api.session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory until sessions need to outlive the process. */
@Component
public class SessionRegistry {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public Session create() {
    var session = new Session(UUID.randomUUID().toString());
    sessions.put(session.id(), session);
    return session;
  }

  public Optional<Session> find(String id) {
    return Optional.ofNullable(sessions.get(id));
  }

  public void remove(String id) {
    sessions.remove(id);
  }
}
