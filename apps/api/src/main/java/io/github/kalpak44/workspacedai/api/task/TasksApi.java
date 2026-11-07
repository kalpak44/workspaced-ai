package io.github.kalpak44.workspacedai.api.task;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Scheduled tasks: the routes and their OpenAPI documentation.
 *
 * @see TasksController
 */
@Tag(
    name = "Tasks",
    description =
        "Prompts a workspace's agent runs on a schedule, each run in a new session. They are"
            + " Markdown files in the workspace, which the agent can edit too. All need the"
            + " workspace running, and tasks run only while it is")
@RequestMapping("/workspaces/{workspaceId}/tasks")
public interface TasksApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listTasks",
      summary = "List a workspace's tasks",
      description =
          "Every task file, in name order, as the workspace holds it: the agent's own included. A"
              + " file without a valid schedule is listed with what could be read of it, and does"
              + " not run.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = TaskFile.class))))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace is not running.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<List<TaskFile>> list(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "saveTask",
      summary = "Create or replace a task",
      description = "Writes the file whole. It runs from the next minute its schedule matches.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = TaskFile.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is not up to 63 letters, digits, - and _; the cron expression or time zone is"
              + " not valid; or the prompt is missing or longer than 100,000 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace is not running.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/{name}", consumes = JSON, produces = JSON)
  ResponseEntity<TaskFile> save(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "The file's name, without .md", example = "pr-summary") @PathVariable
          String name,
      @Valid @RequestBody TaskRequest request);

  @Operation(
      operationId = "deleteTask",
      summary = "Delete a task",
      description = "It no longer runs. Sessions it already started are kept.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no task of that name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace is not running.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{name}")
  ResponseEntity<Void> delete(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "The file's name, without .md") @PathVariable String name);
}
