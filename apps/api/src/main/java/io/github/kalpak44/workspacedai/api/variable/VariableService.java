package io.github.kalpak44.workspacedai.api.variable;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A workspace's variables: its .env file, which the harness reads when it starts, so a change
 * applies from the next start. They exist only as that file, so the user edits here exactly what
 * the agent can edit too. Unlike secrets, their values can be read back. Each call needs the
 * workspace running.
 */
@Service
public class VariableService {

  /** Enough for any configuration, few enough to edit in one form. */
  static final int MAX_VARIABLES = 200;

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public VariableService(
      WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  /** In the file's order. */
  public List<Variable> list(UUID workspaceId) {
    return harness.readVariables(endpoint(workspaceId)).map(EnvFile::parse).orElse(List.of());
  }

  /**
   * Replaces them all: the file is written whole, so comments the agent left in it are not kept. Of
   * two saves at once, one wins, never a mix.
   */
  public List<Variable> save(UUID workspaceId, List<Variable> variables) {
    validate(variables);
    harness.writeVariables(endpoint(workspaceId), EnvFile.format(variables));
    return List.copyOf(variables);
  }

  private static void validate(List<Variable> variables) {
    if (variables.size() > MAX_VARIABLES) {
      throw badRequest("a workspace has at most " + MAX_VARIABLES + " variables");
    }
    var names = new HashSet<String>();
    for (var variable : variables) {
      if (variable.name().startsWith("OPENCODE_")) {
        throw badRequest(variable.name() + " is set by the workspace itself");
      }
      if (variable.value().contains("\n") || variable.value().contains("\r")) {
        throw badRequest(variable.name() + " has a line break; a value is one line");
      }
      if (!names.add(variable.name())) {
        throw badRequest(variable.name() + " is given twice");
      }
    }
  }

  private static ResponseStatusException badRequest(String detail) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
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
