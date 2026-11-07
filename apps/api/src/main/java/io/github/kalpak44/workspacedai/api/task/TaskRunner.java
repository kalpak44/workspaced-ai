package io.github.kalpak44.workspacedai.api.task;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.session.SessionService;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs each running workspace's tasks whose schedule matches the current minute, each in a new
 * session titled with the task's name. The files are read afresh every minute, so a task the user
 * or the agent adds, changes or deletes applies from the next one, with nothing to keep in sync.
 *
 * <p>One API instance runs a minute's pass: the others find the lock held and skip theirs. A minute
 * nothing ran in, because the workspace was stopped or a pass was still running, is not made up
 * later, as with cron.
 */
@Component
public class TaskRunner {

  private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);

  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final TaskService tasks;
  private final SessionService sessions;

  public TaskRunner(
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      TaskService tasks,
      SessionService sessions) {
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.tasks = tasks;
    this.sessions = sessions;
  }

  @Scheduled(cron = "0 * * * * *")
  @SchedulerLock(name = "tasks", lockAtLeastFor = "PT30S", lockAtMostFor = "PT2M")
  public void run() {
    var minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    for (var workspaceId : workspaces.findAllIds()) {
      try {
        runDue(workspaceId, minute);
      } catch (RuntimeException e) {
        LOG.warn("Could not check the tasks of workspace {}", workspaceId, e);
      }
    }
  }

  void runDue(UUID workspaceId, Instant minute) {
    var endpoint = runtime.harness(workspaceId).orElse(null);
    if (endpoint == null) return;
    for (var task : tasks.list(endpoint)) {
      if (task.prompt().isBlank()) continue;
      var schedule = Schedule.of(task.cron(), task.timeZone()).orElse(null);
      if (schedule == null || !schedule.matches(minute)) continue;
      try {
        var session = sessions.create(workspaceId, task.name());
        sessions.send(workspaceId, session.id(), prompt(task));
        LOG.info(
            "Ran task {} of workspace {} in session {}", task.name(), workspaceId, session.id());
      } catch (RuntimeException e) {
        LOG.warn("Could not run task {} of workspace {}", task.name(), workspaceId, e);
      }
    }
  }

  /**
   * Says where the prompt came from, so the agent can tell a scheduled run from a user's message.
   */
  static String prompt(TaskFile task) {
    return "This is a run of the scheduled task \""
        + task.name()
        + "\" ("
        + Harness.TASKS_DIRECTORY.replace(Harness.HOME, "~")
        + "/"
        + task.name()
        + ".md). Nobody is watching while it runs.\n\n"
        + task.prompt();
  }
}
