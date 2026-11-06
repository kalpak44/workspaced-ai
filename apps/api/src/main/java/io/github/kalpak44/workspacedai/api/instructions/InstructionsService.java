package io.github.kalpak44.workspacedai.api.instructions;

import io.github.kalpak44.workspacedai.api.ClusterLocks;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatus;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceStatusChanged;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The instructions every agent is given, on top of the harness image's own guide: a user's general
 * instructions, which apply to all their workspaces, and each workspace's own.
 *
 * <p>The database holds them; the harness reads them from files in the workspace's home. Those are
 * written whenever either changes and whenever the workspace becomes ready, so a new or replaced
 * pod has them before its first message. Writing compares first, so a pod that already has them is
 * not reloaded. No transaction is open while the harness is called.
 */
@Service
public class InstructionsService {

  private static final Logger LOG = LoggerFactory.getLogger(InstructionsService.class);

  private final UserRepository users;
  private final WorkspaceRepository workspaces;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final ClusterLocks locks;
  private final TransactionTemplate transactions;
  // Writes nobody waits on: a user's workspaces after their general instructions change, and a
  // workspace that has just become ready, which is reported on the Kubernetes informer's thread.
  private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

  public InstructionsService(
      UserRepository users,
      WorkspaceRepository workspaces,
      WorkspaceRuntime runtime,
      Harness harness,
      ClusterLocks locks,
      TransactionTemplate transactions) {
    this.users = users;
    this.workspaces = workspaces;
    this.runtime = runtime;
    this.harness = harness;
    this.locks = locks;
    this.transactions = transactions;
  }

  public String general(UUID userId) {
    return users.findById(userId).orElseThrow(InstructionsService::signedOut).getInstructions();
  }

  /** Saved at once; every workspace of the user's that is running gets them in the background. */
  public String setGeneral(UUID userId, String instructions) {
    transactions.executeWithoutResult(
        _ ->
            users
                .findById(userId)
                .orElseThrow(InstructionsService::signedOut)
                .setInstructions(instructions));
    for (var workspace : workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId)) {
      background.execute(() -> applyQuietly(workspace.getId()));
    }
    return instructions;
  }

  /** The workspace's own instructions, and the guide its harness gives the agent. */
  public WorkspaceInstructions workspace(UUID workspaceId) {
    var entity = workspaceEntity(workspaceId);
    var guide =
        runtime
            .harness(workspaceId)
            .map(
                endpoint -> {
                  try {
                    return harness.workspaceGuide(endpoint);
                  } catch (HarnessUnavailableException _) {
                    return null;
                  }
                })
            .orElse(null);
    return new WorkspaceInstructions(entity.getInstructions(), guide);
  }

  /**
   * Saved at once and written to the running workspace before this returns. A workspace that is not
   * running gets them when it next starts; {@code applied} says which happened.
   */
  public SavedInstructions setWorkspace(UUID workspaceId, String instructions) {
    transactions.executeWithoutResult(
        _ -> workspaceEntity(workspaceId).setInstructions(instructions));
    return new SavedInstructions(instructions, apply(workspaceId));
  }

  /** Whatever this instance hears of a workspace becoming ready, as every instance does. */
  @EventListener
  void onStatusChanged(WorkspaceStatusChanged changed) {
    if (changed.status() == WorkspaceStatus.READY) {
      background.execute(() -> applyQuietly(changed.id()));
    }
  }

  /**
   * Reads what to write inside the workspace's lock, so of two writes racing, the one that writes
   * last also read last: an older version can never overwrite a newer one.
   *
   * @return false when the workspace is not running, or is gone
   */
  boolean apply(UUID workspaceId) {
    var endpoint = runtime.harness(workspaceId);
    if (endpoint.isEmpty()) {
      return false;
    }
    return locks.call(
        "instructions:" + workspaceId,
        () -> {
          var workspace = workspaces.findById(workspaceId);
          if (workspace.isEmpty()) {
            return false;
          }
          var general =
              users.findById(workspace.get().getOwnerId()).map(u -> u.getInstructions()).orElse("");
          try {
            harness.writeInstructions(endpoint.get(), general, workspace.get().getInstructions());
            return true;
          } catch (HarnessUnavailableException _) {
            return false;
          }
        });
  }

  private void applyQuietly(UUID workspaceId) {
    try {
      apply(workspaceId);
    } catch (RuntimeException e) {
      LOG.warn(
          "could not write the instructions of workspace {}; it gets them on its next start",
          workspaceId,
          e);
    }
  }

  private WorkspaceEntity workspaceEntity(UUID id) {
    return workspaces
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no workspace " + id));
  }

  private static ResponseStatusException signedOut() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in first");
  }
}
