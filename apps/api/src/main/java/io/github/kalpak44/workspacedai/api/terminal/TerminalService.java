package io.github.kalpak44.workspacedai.api.terminal;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.harness.TerminalSession;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Workspace-scoped PTY lifecycle; OpenCode owns the processes and retained metadata. */
@Service
public class TerminalService {
  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public TerminalService(
      WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  public List<TerminalSession> list(UUID workspaceId) {
    return harness.terminals(endpoint(workspaceId), Harness.PROJECT_ROOT);
  }

  public TerminalSession create(UUID workspaceId, String title) {
    return harness.createTerminal(endpoint(workspaceId), Harness.PROJECT_ROOT, title.trim());
  }

  public TerminalSession get(UUID workspaceId, String id) {
    return info(endpoint(workspaceId), id);
  }

  public void delete(UUID workspaceId, String id) {
    validateId(id);
    harness.deleteTerminal(endpoint(workspaceId), Harness.PROJECT_ROOT, id);
  }

  public WorkspaceTerminal connect(
      UUID workspaceId, String id, OutputStream output, Runnable onExit) {
    var endpoint = endpoint(workspaceId);
    var terminal = info(endpoint, id);
    if (!"running".equals(terminal.status())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "this terminal has exited");
    }
    return harness.connectTerminal(endpoint, Harness.PROJECT_ROOT, id, output, onExit);
  }

  private TerminalSession info(HarnessEndpoint endpoint, String id) {
    validateId(id);
    return harness
        .terminalInfo(endpoint, Harness.PROJECT_ROOT, id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such terminal"));
  }

  private static void validateId(String id) {
    if (id == null || !id.matches("pty[A-Za-z0-9_-]{1,200}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid terminal id");
    }
  }

  private HarnessEndpoint endpoint(UUID workspaceId) {
    if (!workspaces.existsById(workspaceId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such workspace");
    }
    return runtime
        .harness(workspaceId)
        .orElseThrow(() -> new HarnessUnavailableException("the workspace is not running"));
  }
}
