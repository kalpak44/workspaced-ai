package io.github.kalpak44.workspacedai.api.backup;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A backup of one workspace: its whole home, and the settings beside it")
public record Backup(
    UUID id,
    @Schema(description = "The workspace it was made of, which may since have been deleted")
        UUID workspaceId,
    @Schema(description = "The workspace's name now, or when the backup was made if it is gone")
        String workspaceName,
    @Schema(description = "Whether that workspace still exists, so it can be restored in place")
        boolean workspaceExists,
    BackupStatus status,
    @Schema(
            description = "What a running backup is doing",
            allowableValues = {"preparing", "archiving", "uploading", "done", "failed"})
        String phase,
    @Schema(description = "0 to 100", example = "42") int progress,
    @Schema(description = "The archive's size once it is stored") Long sizeBytes,
    @Schema(description = "Why it failed") String error,
    Instant createdAt,
    Instant completedAt,
    String modelProvider,
    @Schema(description = "The workspace's model; null for the harness's default") String model,
    boolean sessionTtlEnabled,
    int sessionTtlHours,
    @Schema(description = "Secret names only: their values are not kept, so add them again")
        List<String> secrets,
    List<String> variables,
    List<String> instructions,
    List<String> tasks,
    @Schema(description = "How many conversations it holds") int sessions) {

  static Backup of(BackupEntity entity, BackupManifest manifest, String name, boolean exists) {
    return new Backup(
        entity.getId(),
        entity.getWorkspaceId(),
        name,
        exists,
        entity.getStatus(),
        entity.getPhase(),
        entity.getProgress(),
        entity.getSizeBytes(),
        entity.getError(),
        entity.getCreatedAt(),
        entity.getCompletedAt(),
        manifest.modelProvider(),
        manifest.model(),
        manifest.sessionTtlEnabled(),
        manifest.sessionTtlHours(),
        manifest.secrets(),
        manifest.variables(),
        manifest.instructions(),
        manifest.tasks(),
        manifest.sessions().size());
  }
}
