package io.github.kalpak44.workspacedai.api.recipe;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recipe drafts: files in a workspace's home that its agent writes when asked for a recipe, and the
 * app writes when the user asks the agent to revise one. Publishing one is the user's step, through
 * the recipe routes. Each needs the workspace running.
 */
@Service
public class RecipeDraftsService {

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public RecipeDraftsService(
      WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  /** In name order. A file removed between listing and reading is left out. */
  public List<RecipeDraft> list(UUID workspaceId) {
    var endpoint = endpoint(workspaceId);
    return harness.recipeDrafts(endpoint).stream()
        .flatMap(
            name ->
                harness
                    .readRecipeDraft(endpoint, name)
                    .map(content -> new RecipeDraft(name, content))
                    .stream())
        .toList();
  }

  public RecipeDraft save(UUID workspaceId, String name, String content) {
    validate(name);
    harness.writeRecipeDraft(endpoint(workspaceId), name, content);
    return new RecipeDraft(name, content);
  }

  public void delete(UUID workspaceId, String name) {
    validate(name);
    var endpoint = endpoint(workspaceId);
    if (!harness.recipeDrafts(endpoint).contains(name)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "the workspace has no recipe draft " + name);
    }
    harness.deleteRecipeDraft(endpoint, name);
  }

  private static void validate(String name) {
    if (name == null || !name.matches(Harness.INSTRUCTION_NAME_PATTERN)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "a recipe draft's name is up to 63 letters, digits, - and _, starting with a letter or"
              + " digit");
    }
  }

  private HarnessEndpoint endpoint(UUID workspaceId) {
    if (!workspaces.existsById(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + workspaceId);
    }
    return runtime
        .harness(workspaceId)
        .orElseThrow(
            () -> new HarnessUnavailableException("the workspace's harness is not running"));
  }
}
