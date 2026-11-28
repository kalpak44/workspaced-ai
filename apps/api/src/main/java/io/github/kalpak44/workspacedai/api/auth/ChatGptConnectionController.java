package io.github.kalpak44.workspacedai.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/workspaces/{workspace}/chatgpt")
public class ChatGptConnectionController {
  public record Start(
      @NotBlank @Size(max = 100) String name,
      @NotNull @AssertTrue Boolean providerNoticeAccepted) {}

  private final ChatGptConnections connections;

  public ChatGptConnectionController(ChatGptConnections connections) {
    this.connections = connections;
  }

  @PostMapping
  public ResponseEntity<ChatGptConnections.Attempt> start(
      @PathVariable UUID workspace, @Valid @RequestBody Start request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(connections.start(workspace, request.name().strip()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ChatGptConnections.Status> poll(
      @PathVariable UUID workspace, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(connections.poll(workspace, id));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable UUID workspace, @PathVariable UUID id) {
    connections.cancel(workspace, id);
  }
}
