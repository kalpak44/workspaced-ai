package io.github.kalpak44.workspacedai.api.backup;

import io.github.kalpak44.workspacedai.api.workspace.Workspace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Backup API: the routes and their OpenAPI documentation. A backup is made from a workspace's own
 * route, which checks its owner; everything else is under /backups, since a backup outlives its
 * workspace.
 *
 * @see BackupController
 */
@Tag(name = "Backups", description = "Backups of a workspace's home and settings")
public interface BackupApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "startBackup",
      summary = "Back up a workspace",
      description =
          "Records the workspace's settings, then has the workspace archive its whole home and "
              + "upload it. Answers at once with the backup RUNNING; poll GET /backups/{id} for "
              + "its progress until it is COMPLETED or FAILED.")
  @ApiResponse(
      responseCode = "202",
      description = "Started.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Backup.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "A backup of the workspace is already running, or its harness predates backups and "
              + "needs a restart.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace is not running, or backups are not configured.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/workspaces/{id}/backups", produces = JSON)
  ResponseEntity<Backup> start(@Parameter(description = "Workspace id") @PathVariable UUID id);

  @Operation(
      operationId = "listBackups",
      summary = "List backups",
      description =
          "Every backup of the caller's workspaces, newest first, deleted workspaces included.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Backup.class))))
  @GetMapping(path = "/backups", produces = JSON)
  ResponseEntity<List<Backup>> list();

  @Operation(operationId = "getBackup", summary = "Get a backup, with its progress")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Backup.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No backup of the caller's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/backups/{id}", produces = JSON)
  ResponseEntity<Backup> get(@Parameter(description = "Backup id") @PathVariable UUID id);

  @Operation(
      operationId = "downloadBackup",
      summary = "Link to download a backup's archive",
      description =
          "A signed link, valid for 15 minutes, to the archive: a gzipped tar of the workspace's "
              + "home. The settings are not in it; they are on the backup itself.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = BackupDownload.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No backup of the caller's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The backup is not completed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/backups/{id}/download", produces = JSON)
  ResponseEntity<BackupDownload> download(
      @Parameter(description = "Backup id") @PathVariable UUID id);

  @Operation(
      operationId = "deleteBackup",
      summary = "Delete a backup",
      description = "Removes the archive from storage, then the backup.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No backup of the caller's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The backup is still being made.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/backups/{id}")
  ResponseEntity<Void> delete(@Parameter(description = "Backup id") @PathVariable UUID id);

  @Operation(
      operationId = "restoreBackup",
      summary = "Restore a backup into its workspace",
      description =
          "Replaces the whole home of the workspace the backup was made of, and its model, "
              + "session TTL and conversations, then restarts it; the new pod restores the archive "
              + "before it starts. Secrets keep their current values. Follow /workspaces/events "
              + "for INITIALIZING and then READY.")
  @ApiResponse(responseCode = "202", description = "Restoring.")
  @ApiResponse(
      responseCode = "404",
      description = "No such backup, or its workspace is gone.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The backup is not completed, or the workspace is being backed up.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/backups/{id}/restore")
  ResponseEntity<Void> restore(@Parameter(description = "Backup id") @PathVariable UUID id);

  @Operation(
      operationId = "createWorkspaceFromBackup",
      summary = "Create a workspace from a backup",
      description =
          "A new workspace with the backup's home and settings. Without an apiKey, the key of "
              + "the workspace the backup was made of is reused, while it exists. Secrets are not "
              + "in a backup; the backup lists their names so they can be added again.")
  @ApiResponse(
      responseCode = "201",
      description = "Created.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Workspace.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The name is blank or too long, or no key was given and none can be reused.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The backup is not completed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "422",
      description = "The model provider rejected the API key; nothing was created.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/backups/{id}/workspaces", consumes = JSON, produces = JSON)
  ResponseEntity<Workspace> createWorkspace(
      @Parameter(description = "Backup id") @PathVariable UUID id,
      @Valid @RequestBody CloneBackupRequest request);
}
