package io.github.kalpak44.workspacedai.api.task;

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
 * A workspace's scheduled tasks: Markdown files in its home, as instructions are, so the user edits
 * here exactly what {@link TaskRunner} runs, and the agent can add and change them as well. Each
 * needs the workspace running.
 */
@Service
public class TaskService {

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;

  public TaskService(WorkspaceRepository workspaces, WorkspaceRuntime runtime, Harness harness) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
  }

  /**
   * In name order, the agent's own included. A file it wrote without a valid schedule is listed as
   * it is, for the user to fix; a file removed between listing and reading is left out.
   */
  public List<TaskFile> list(UUID workspaceId) {
    return list(endpoint(workspaceId));
  }

  List<TaskFile> list(HarnessEndpoint endpoint) {
    return harness.taskFiles(endpoint).stream()
        .flatMap(
            name ->
                harness.readTask(endpoint, name).map(text -> TaskFile.parse(name, text)).stream())
        .toList();
  }

  /** Creates the file, or replaces it whole: of two saves at once, one wins, never a mix. */
  public TaskFile save(UUID workspaceId, String name, TaskRequest request) {
    validate(name);
    var error = Schedule.error(request.cron(), request.timeZone());
    if (error != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
    }
    var task =
        new TaskFile(
            name, request.cron().strip(), request.timeZone().strip(), request.prompt().strip());
    harness.writeTask(endpoint(workspaceId), name, task.toMarkdown());
    return task;
  }

  public void delete(UUID workspaceId, String name) {
    validate(name);
    var endpoint = endpoint(workspaceId);
    if (!harness.taskFiles(endpoint).contains(name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "the workspace has no task " + name);
    }
    harness.deleteTask(endpoint, name);
  }

  private static void validate(String name) {
    if (name == null || !name.matches(Harness.INSTRUCTION_NAME_PATTERN)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "a task's name is up to 63 letters, digits, - and _, starting with a letter or digit");
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
