package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link SessionApi}. */
@RestController
public class SessionController implements SessionApi {

  private final SessionService service;
  private final SessionStreams streams;

  public SessionController(SessionService service, SessionStreams streams) {
    this.service = service;
    this.streams = streams;
  }

  @Override
  public ResponseEntity<List<Session>> list(UUID workspaceId) {
    return ResponseEntity.ok(service.list(workspaceId));
  }

  @Override
  public ResponseEntity<Session> create(UUID workspaceId) {
    var session = service.create(workspaceId);
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(session.id())
            .toUri();
    return ResponseEntity.created(location).body(session);
  }

  @Override
  public ResponseEntity<Void> delete(UUID workspaceId, UUID sessionId) {
    service.delete(workspaceId, sessionId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<List<ChatMessage>> messages(UUID workspaceId, UUID sessionId) {
    return ResponseEntity.ok(service.messages(workspaceId, sessionId));
  }

  @Override
  public ResponseEntity<Void> send(UUID workspaceId, UUID sessionId, MessageRequest request) {
    service.send(workspaceId, sessionId, request.text());
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<Void> abort(UUID workspaceId, UUID sessionId) {
    service.abort(workspaceId, sessionId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<SseEmitter> events(UUID workspaceId, UUID sessionId) {
    return ResponseEntity.ok(streams.subscribe(service.target(workspaceId, sessionId)));
  }
}
