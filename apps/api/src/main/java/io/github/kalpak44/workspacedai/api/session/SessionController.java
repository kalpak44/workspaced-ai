package io.github.kalpak44.workspacedai.api.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Output flows one way over SSE; everything the client says back is a plain POST. See docs/api.md
 * for the event types.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

  private final SessionRegistry registry;
  private final DemoAgent agent;

  public SessionController(SessionRegistry registry, DemoAgent agent) {
    this.registry = registry;
    this.agent = agent;
  }

  public record Answer(@NotBlank String answer) {}

  public record Decision(@NotBlank @Pattern(regexp = "approve|reject") String decision) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, String> create() {
    return Map.of("id", registry.create().id());
  }

  /** Opening the stream starts the run, so no event can be sent before anyone is listening. */
  @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id) {
    var session = session(id);
    // 0 disables the servlet async timeout; the agent ends the stream itself.
    var emitter = new SseEmitter(0L);
    if (!session.attach(emitter)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "session " + id + " is already open");
    }
    agent.start(session);
    return emitter;
  }

  @PostMapping("/{id}/questions/{questionId}/answer")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void answer(
      @PathVariable String id, @PathVariable String questionId, @Valid @RequestBody Answer body) {
    resolve(id, questionId, body.answer());
  }

  @PostMapping("/{id}/commands/{commandId}/decision")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void decide(
      @PathVariable String id, @PathVariable String commandId, @Valid @RequestBody Decision body) {
    resolve(id, commandId, body.decision());
  }

  private void resolve(String id, String promptId, String value) {
    if (!session(id).resolve(promptId, value)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing is waiting on " + promptId);
    }
  }

  private Session session(String id) {
    return registry
        .find(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no session " + id));
  }
}
