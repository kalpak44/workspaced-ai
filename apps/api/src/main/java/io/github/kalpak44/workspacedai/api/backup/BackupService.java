package io.github.kalpak44.workspacedai.api.backup;

import io.github.kalpak44.workspacedai.api.harness.Harness;
import io.github.kalpak44.workspacedai.api.harness.HarnessEndpoint;
import io.github.kalpak44.workspacedai.api.harness.HarnessUnavailableException;
import io.github.kalpak44.workspacedai.api.provider.ModelProvider;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.secret.WorkspaceSecretEntity;
import io.github.kalpak44.workspacedai.api.secret.WorkspaceSecretRepository;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionEntity;
import io.github.kalpak44.workspacedai.api.session.WorkspaceSessionRepository;
import io.github.kalpak44.workspacedai.api.variable.Variable;
import io.github.kalpak44.workspacedai.api.variable.VariableService;
import io.github.kalpak44.workspacedai.api.workspace.Workspace;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceEntity;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backups of a workspace: its whole home, archived and uploaded by the workspace itself, plus the
 * settings the database holds for it. A backup outlives its workspace, so it can be restored in
 * place while the workspace exists and made into a new one at any time.
 */
@Service
public class BackupService {

  private static final String RESTORE_PROGRESS = "workspace-backup progress ";
  // The restore reports once a second, so its last report is always among these.
  private static final int RESTORE_LOG_LINES = 20;

  // Long enough to start a download, short enough that a link pasted somewhere soon stops working.
  private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(15);

  private final BackupRepository backups;
  private final WorkspaceRepository workspaces;
  private final WorkspaceSessionRepository sessions;
  private final WorkspaceSecretRepository secrets;
  private final WorkspaceService workspaceService;
  private final VariableService variables;
  private final WorkspaceRuntime runtime;
  private final Harness harness;
  private final BackupStorage storage;
  private final BackupProgress progress;
  private final BackupProperties properties;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  public BackupService(
      BackupRepository backups,
      WorkspaceRepository workspaces,
      WorkspaceSessionRepository sessions,
      WorkspaceSecretRepository secrets,
      WorkspaceService workspaceService,
      VariableService variables,
      WorkspaceRuntime runtime,
      Harness harness,
      BackupStorage storage,
      BackupProgress progress,
      BackupProperties properties,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.backups = backups;
    this.workspaces = workspaces;
    this.sessions = sessions;
    this.secrets = secrets;
    this.workspaceService = workspaceService;
    this.variables = variables;
    this.runtime = runtime;
    this.harness = harness;
    this.storage = storage;
    this.progress = progress;
    this.properties = properties;
    this.transactions = transactions;
    this.json = json;
  }

  /**
   * Records the settings, then has the workspace archive its home and upload it, and returns while
   * that runs; the backup's progress is on its row. The upload link is signed before anything is
   * stored, so a store that is not configured leaves nothing behind.
   */
  public Backup start(UUID workspaceId) {
    var workspace =
        workspaces
            .findById(workspaceId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no workspace " + workspaceId));
    var endpoint =
        runtime
            .harness(workspaceId)
            .orElseThrow(
                () -> new HarnessUnavailableException("the workspace's harness is not running"));
    if (backups.existsByWorkspaceIdAndStatus(workspaceId, BackupStatus.RUNNING)) {
      throw alreadyRunning();
    }
    var manifest = manifest(workspace, endpoint);
    var entity =
        new BackupEntity(
            UUID.randomUUID(),
            workspace.getOwnerId(),
            workspaceId,
            workspace.getName(),
            json.writeValueAsString(manifest));
    var uploadUrl = storage.uploadUrl(entity.getObjectKey(), properties.uploadTtl());
    try {
      backups.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      // Another request started one between the check above and this insert.
      throw alreadyRunning();
    }
    boolean started;
    try {
      started = harness.startBackup(endpoint, entity.getId().toString(), uploadUrl);
    } catch (RuntimeException e) {
      backups.fail(entity.getId(), "the workspace could not start the backup", now());
      throw e;
    }
    if (!started) {
      backups.fail(entity.getId(), "the workspace's harness predates backups", now());
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "the workspace's harness predates backups; restart the workspace to update it");
    }
    progress.follow(entity.getId(), workspaceId, entity.getObjectKey());
    return Backup.of(entity, manifest, workspace.getName(), true);
  }

  /**
   * Read from the workspace's own log: the restore runs before its harness, so the log is all there
   * is. Empty when the newest pod is not restoring, as after an ordinary restart.
   */
  public Optional<RestoreProgress> restoreProgress(UUID workspaceId) {
    return latestRestoreProgress(runtime.recentLog(workspaceId, RESTORE_LOG_LINES), json);
  }

  /** The last progress line of a log, if it has one that reads. */
  static Optional<RestoreProgress> latestRestoreProgress(List<String> lines, JsonMapper json) {
    for (var i = lines.size() - 1; i >= 0; i--) {
      var line = lines.get(i);
      var at = line.indexOf(RESTORE_PROGRESS);
      if (at < 0) {
        continue;
      }
      try {
        var report = json.readTree(line.substring(at + RESTORE_PROGRESS.length()));
        var phase = report.path("phase").asString("");
        var done = report.path("done").asLong(0);
        var total = report.path("total").asLong(0);
        return Optional.of(new RestoreProgress(phase, done, total, overall(phase, done, total)));
      } catch (JacksonException _) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  /** Extracting takes most of a restore on a network volume, one round trip per file. */
  static int overall(String phase, long done, long total) {
    return switch (phase) {
      case "downloading" -> BackupProgress.share(done, total, 0, 20);
      case "checking" -> BackupProgress.share(done, total, 20, 30);
      case "extracting" -> BackupProgress.share(done, total, 30, 99);
      case "done" -> 100;
      default -> 0;
    };
  }

  /** Every backup the user has, newest first, named as each workspace is now if it still is. */
  public List<Backup> list(UUID ownerId) {
    var names =
        workspaces.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId).stream()
            .collect(Collectors.toMap(WorkspaceEntity::getId, WorkspaceEntity::getName));
    return backups.findAllByOwnerIdOrderByCreatedAtDescIdDesc(ownerId).stream()
        .map(b -> dto(b, names))
        .toList();
  }

  public Backup get(UUID ownerId, UUID backupId) {
    var backup = owned(ownerId, backupId);
    var name = workspaces.findById(backup.getWorkspaceId()).map(WorkspaceEntity::getName);
    return dto(backup, name.map(n -> Map.of(backup.getWorkspaceId(), n)).orElse(Map.of()));
  }

  /**
   * A short-lived link to the archive itself, a gzipped tar of the home, so it can be kept outside
   * the homelab or unpacked by hand.
   */
  public BackupDownload download(UUID ownerId, UUID backupId) {
    var backup = completed(ownerId, backupId);
    var expiresAt = now().plus(DOWNLOAD_TTL);
    return new BackupDownload(storage.downloadUrl(backup.getObjectKey(), DOWNLOAD_TTL), expiresAt);
  }

  /** The archive goes first, so a failure leaves the row to retry the delete from. */
  public void delete(UUID ownerId, UUID backupId) {
    var backup = owned(ownerId, backupId);
    if (backup.getStatus() == BackupStatus.RUNNING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the backup is still being made");
    }
    if (backup.getStatus() == BackupStatus.COMPLETED) {
      storage.delete(backup.getObjectKey());
    }
    backups.deleteById(backupId);
  }

  /**
   * Puts the backup back into the workspace it was made of, replacing its whole home, and restarts
   * it: the new pod restores the archive before OpenCode starts, so nothing reads a half-restored
   * home. The settings and the session index follow the backup; secrets keep their current values,
   * since the backup never had them.
   */
  public void restore(UUID ownerId, UUID backupId) {
    var backup = completed(ownerId, backupId);
    var workspaceId = backup.getWorkspaceId();
    if (workspaces.findOwnerIdById(workspaceId).filter(ownerId::equals).isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "the workspace this backup was made of is gone; create a new one from it instead");
    }
    if (backups.existsByWorkspaceIdAndStatus(workspaceId, BackupStatus.RUNNING)) {
      throw alreadyRunning();
    }
    var manifest = manifest(backup);
    var url = storage.downloadUrl(backup.getObjectKey(), properties.restoreTtl());
    runtime.putSecret(workspaceId, Harness.RESTORE_URL_VARIABLE, url.toString());
    runtime.putSecret(workspaceId, Harness.RESTORE_ID_VARIABLE, UUID.randomUUID().toString());
    transactions.executeWithoutResult(_ -> apply(workspaceId, manifest));
    workspaceService.restart(workspaceId);
  }

  /**
   * A new workspace that starts from the backup. The provider's key is the one given, or else the
   * one the backed-up workspace still has; it is checked with the provider either way.
   */
  public Workspace createFrom(UUID ownerId, UUID backupId, String name, String apiKey) {
    var backup = completed(ownerId, backupId);
    var manifest = manifest(backup);
    var provider =
        ModelProvider.byId(manifest.modelProvider())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "the backup's model provider " + manifest.modelProvider() + " is gone"));
    var key = apiKey != null ? apiKey : reusedKey(ownerId, backup, provider);
    var url = storage.downloadUrl(backup.getObjectKey(), properties.restoreTtl());
    var created =
        workspaceService.create(
            ownerId,
            name,
            provider,
            key,
            Map.of(
                Harness.RESTORE_URL_VARIABLE,
                url.toString(),
                Harness.RESTORE_ID_VARIABLE,
                UUID.randomUUID().toString()));
    transactions.executeWithoutResult(_ -> apply(created.id(), manifest));
    return new Workspace(
        created.id(),
        created.name(),
        created.modelProvider(),
        manifest.model(),
        created.status(),
        created.restartRequired());
  }

  private String reusedKey(UUID ownerId, BackupEntity backup, ModelProvider provider) {
    var source = backup.getWorkspaceId();
    if (workspaces.findOwnerIdById(source).filter(ownerId::equals).isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "the workspace this backup was made of is gone, so give an API key for the new one");
    }
    return runtime
        .secret(source, provider.apiKeyVariable())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "the workspace this backup was made of has no key to reuse; give one"));
  }

  /** The model, the session TTL and the session index, as the backup has them. */
  private void apply(UUID workspaceId, BackupManifest manifest) {
    var workspace = workspaces.lockById(workspaceId).orElseThrow();
    workspace.setModel(manifest.model());
    workspace.setSessionTtl(manifest.sessionTtlEnabled(), manifest.sessionTtlHours());
    sessions.deleteAll(sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId));
    sessions.flush();
    for (var session : manifest.sessions()) {
      var entity =
          new WorkspaceSessionEntity(
              UUID.randomUUID(),
              workspaceId,
              session.path(),
              session.harnessSessionId(),
              session.createdAt());
      entity.setTitle(session.title());
      sessions.save(entity);
    }
  }

  private BackupManifest manifest(WorkspaceEntity workspace, HarnessEndpoint endpoint) {
    var id = workspace.getId();
    return new BackupManifest(
        workspace.getModelProvider(),
        workspace.getModel(),
        workspace.isSessionTtlEnabled(),
        workspace.getSessionTtlHours(),
        secrets.findAllByWorkspaceIdOrderByCreatedAtAscNameAsc(id).stream()
            .map(WorkspaceSecretEntity::getName)
            .toList(),
        variables.list(id).stream().map(Variable::name).toList(),
        harness.instructionFiles(endpoint),
        harness.taskFiles(endpoint),
        sessions.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(id).stream()
            .map(
                s ->
                    new BackupManifest.Session(
                        s.getPath(), s.getHarnessSessionId(), s.getTitle(), s.getCreatedAt()))
            .toList());
  }

  private BackupManifest manifest(BackupEntity backup) {
    return json.readValue(backup.getManifest(), BackupManifest.class);
  }

  private Backup dto(BackupEntity backup, Map<UUID, String> currentNames) {
    var current = currentNames.get(backup.getWorkspaceId());
    return Backup.of(
        backup,
        manifest(backup),
        current != null ? current : backup.getWorkspaceName(),
        current != null);
  }

  /** Someone else's backup answers exactly as one that does not exist. */
  private BackupEntity owned(UUID ownerId, UUID backupId) {
    return backups
        .findByIdAndOwnerId(backupId, ownerId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no backup " + backupId));
  }

  private BackupEntity completed(UUID ownerId, UUID backupId) {
    var backup = owned(ownerId, backupId);
    if (backup.getStatus() != BackupStatus.COMPLETED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "only a completed backup can be restored or downloaded");
    }
    return backup;
  }

  private static ResponseStatusException alreadyRunning() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "a backup of this workspace is already being made");
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
