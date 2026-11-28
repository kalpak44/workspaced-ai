package io.github.kalpak44.workspacedai.api.workspace;

import io.github.kalpak44.workspacedai.api.auth.CurrentUser;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Routes, validation and documentation are declared on {@link WorkspaceApi}. */
@RestController
public class WorkspaceController implements WorkspaceApi {

  private final WorkspaceService service;
  private final WorkspaceEvents events;
  private final io.github.kalpak44.workspacedai.api.auth.AiConnectorService connectors;

  public WorkspaceController(
      WorkspaceService service,
      WorkspaceEvents events,
      io.github.kalpak44.workspacedai.api.auth.AiConnectorService connectors) {
    this.service = service;
    this.events = events;
    this.connectors = connectors;
  }

  @Override
  public ResponseEntity<SseEmitter> events() {
    return ResponseEntity.ok(events.subscribe(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<List<Workspace>> list() {
    return ResponseEntity.ok(service.list(CurrentUser.id()));
  }

  @Override
  public ResponseEntity<Workspace> create(CreateWorkspaceRequest request) {
    var provider =
        ModelProvider.byId(request.modelProvider())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unknown model provider " + request.modelProvider()));
    if (request.connectorId() != null && request.apiKey() != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "choose a connector or supply an API key");
    }
    var apiKey =
        request.connectorId() == null
            ? request.trimmedApiKey()
            : connectors.key(request.connectorId(), provider.id());
    if (Boolean.TRUE.equals(request.useChatGptPlan())
        && (provider != ModelProvider.OPENAI
            || request.apiKey() != null
            || request.connectorId() != null
            || request.wantsDemo())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "ChatGPT setup requires OpenAI without an API key or saved connector, on a paid workspace");
    }
    var workspace =
        Boolean.TRUE.equals(request.useChatGptPlan())
            ? service.createChatGpt(
                CurrentUser.id(),
                request.trimmedName(),
                request.serverTypeOrNull(),
                request.timeZoneOrUtc())
            : apiKey == null
                ? service.createDraft(
                    CurrentUser.id(),
                    request.trimmedName(),
                    provider,
                    request.serverTypeOrNull(),
                    request.timeZoneOrUtc(),
                    request.wantsDemo())
                : service.create(
                    CurrentUser.id(),
                    request.trimmedName(),
                    provider,
                    apiKey,
                    request.serverTypeOrNull(),
                    request.timeZoneOrUtc(),
                    request.wantsDemo());
    if (request.connectorId() != null) connectors.remember(workspace.id(), request.connectorId());
    var location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(workspace.id())
            .toUri();
    return ResponseEntity.created(location).body(workspace);
  }

  @Override
  public ResponseEntity<Workspace> launch(UUID id, LaunchWorkspaceRequest request) {
    if ((request.apiKey() == null) == (request.connectorId() == null))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose a connector or supply an API key");
    var key =
        request.connectorId() == null
            ? request.trimmedApiKey()
            : connectors.keyForWorkspace(request.connectorId(), id);
    var launched = service.launch(id, key).orElseThrow(() -> notFound(id));
    if (request.connectorId() != null) connectors.remember(id, request.connectorId());
    return ResponseEntity.ok(launched);
  }

  @Override
  public ResponseEntity<Workspace> rename(UUID id, WorkspaceRequest request) {
    return service
        .rename(id, CurrentUser.id(), request.trimmedName())
        .map(ResponseEntity::ok)
        .orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Workspace> setTimeZone(UUID id, TimeZoneRequest request) {
    return service
        .setTimeZone(id, request.timeZone().strip())
        .map(workspace -> service.asSeenBy(workspace, CurrentUser.id()))
        .map(ResponseEntity::ok)
        .orElseThrow(() -> notFound(id));
  }

  @Override
  public ResponseEntity<Void> delete(UUID id) {
    requireOwner(id);
    if (!service.delete(id)) {
      throw notFound(id);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> restart(UUID id) {
    if (!service.restart(id)) {
      throw notFound(id);
    }
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<Void> completeTutorial(UUID id) {
    if (!service.completeTutorial(id)) throw notFound(id);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Workspace> keep(UUID id) {
    requireOwner(id);
    return ResponseEntity.ok(service.keep(id).orElseThrow(() -> notFound(id)));
  }

  /**
   * A collaborator may do everything in a workspace but end it or pay for it: both are its owner's
   * money. A workspace that does not exist is left for the call itself to answer 404.
   */
  private void requireOwner(UUID id) {
    if (service.ownerOf(id).filter(owner -> !owner.equals(CurrentUser.id())).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "only the workspace's owner can do that");
    }
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id);
  }
}
