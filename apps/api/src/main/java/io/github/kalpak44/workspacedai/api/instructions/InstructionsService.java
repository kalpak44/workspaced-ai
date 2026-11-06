package io.github.kalpak44.workspacedai.api.instructions;

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
 * A workspace's instructions: Markdown files in its home, every one of which its agent is given in
 * every session, after the harness image's read-only guide. They exist only as those files, so the
 * user edits here exactly what the agent reads, and the agent can edit them as well. Each needs the
 * workspace running.
 */
@Service
public class InstructionsService {

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public InstructionsService(
      WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  /** In name order. A file removed between listing and reading is left out. */
  public List<InstructionFile> list(UUID workspaceId) {
    var endpoint = endpoint(workspaceId);
    return harness.instructionFiles(endpoint).stream()
        .flatMap(
            name ->
                harness
                    .readInstructions(endpoint, name)
                    .map(content -> new InstructionFile(name, content))
                    .stream())
        .toList();
  }

  /** Creates the file, or replaces it whole: of two saves at once, one wins, never a mix. */
  public InstructionFile save(UUID workspaceId, String name, String content) {
    validate(name);
    harness.writeInstructions(endpoint(workspaceId), name, content);
    return new InstructionFile(name, content);
  }

  public void delete(UUID workspaceId, String name) {
    validate(name);
    var endpoint = endpoint(workspaceId);
    if (!harness.instructionFiles(endpoint).contains(name)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "the workspace has no instructions " + name);
    }
    harness.deleteInstructions(endpoint, name);
  }

  private static void validate(String name) {
    if (name == null || !name.matches(Harness.INSTRUCTION_NAME_PATTERN)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "an instruction file's name is up to 63 letters, digits, - and _, starting with a letter"
              + " or digit");
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
