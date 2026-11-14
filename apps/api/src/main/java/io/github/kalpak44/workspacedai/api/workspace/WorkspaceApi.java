package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Workspace API: the routes and their OpenAPI documentation, kept apart from the implementation so
 * the controller reads as behaviour only.
 *
 * @see WorkspaceController
 */
@Tag(name = "Workspaces", description = "The workspaces AI agents run in")
@RequestMapping("/workspaces")
public interface WorkspaceApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  String WORKSPACE_EXAMPLE =
      "{\"id\":\"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\",\"name\":\"Research\","
          + "\"modelProvider\":\"openrouter\",\"status\":\"READY\",\"restartRequired\":false}";

  String CREATED_EXAMPLE =
      "{\"id\":\"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\",\"name\":\"Research\","
          + "\"modelProvider\":\"openrouter\",\"serverType\":\"cx23\","
          + "\"model\":null,\"status\":\"INITIALIZING\","
          + "\"restartRequired\":false}";

  String REJECTED_KEY_EXAMPLE =
      "{\"type\":\"about:blank\",\"title\":\"Unprocessable Content\",\"status\":422,"
          + "\"detail\":\"OpenRouter rejected the API key\",\"instance\":\"/api/v1/workspaces\"}";

  String BAD_REQUEST_EXAMPLE =
      "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
          + "\"detail\":\"Invalid request content.\",\"instance\":\"/api/v1/workspaces\"}";

  String NOT_FOUND_EXAMPLE =
      "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
          + "\"detail\":\"no workspace 3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\","
          + "\"instance\":\"/api/v1/workspaces/3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\"}";

  /**
   * Lists every workspace.
   *
   * @return the workspaces, oldest first
   */
  @Operation(
      operationId = "listWorkspaces",
      summary = "List workspaces",
      description =
          "Every workspace, in the order they were created, with the status of its runtime. "
              + "Follow later changes on /workspaces/events.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Workspace.class)),
              examples = @ExampleObject("[" + WORKSPACE_EXAMPLE + "]")))
  @GetMapping(produces = JSON)
  ResponseEntity<List<Workspace>> list();

  /**
   * Streams workspace status changes.
   *
   * @return a stream that stays open until the client closes it
   */
  @Operation(
      operationId = "streamWorkspaceEvents",
      summary = "Stream workspace status changes",
      description =
          "Server-sent events. On connect, one `status` event per existing workspace; after "
              + "that, one each time a workspace's status changes. A `deleted` event (`{\"id\":…}`) follows each deletion. A status may repeat. A "
              + "`keepalive` comment is sent every 30 seconds.")
  @ApiResponse(
      responseCode = "200",
      description = "An open event stream.",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              examples =
                  @ExampleObject(
                      """
                      event:status
                      data:{"id":"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11","status":"READY"}

                      """)))
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> events();

  /**
   * Creates a workspace.
   *
   * @param request the name, trimmed before it is stored, the model provider and its API key
   * @return the workspace with its new id, and its URL in {@code Location}
   */
  @Operation(
      operationId = "createWorkspace",
      summary = "Create a workspace",
      description =
          "Checks the API key with the model provider when one is given, then assigns the id "
              + "and creates the workspace's VM, of the chosen serverType or the default one; it "
              + "is INITIALIZING until the VM reports ready. The key and the provider's base URL "
              + "reach the runtime only; the key is not stored by the API. Surrounding whitespace "
              + "is trimmed from the name and the key.")
  @ApiResponse(
      responseCode = "201",
      description = "Created; its URL is in the Location header.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Workspace.class),
              examples = @ExampleObject(CREATED_EXAMPLE)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is blank or longer than 100 characters, the model provider is not one "
              + "/model-providers lists, or the serverType is not one /vm-sizes lists.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(BAD_REQUEST_EXAMPLE)))
  @ApiResponse(
      responseCode = "422",
      description = "The model provider rejected the API key; nothing was created.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(REJECTED_KEY_EXAMPLE)))
  @ApiResponse(
      responseCode = "502",
      description =
          "The model provider could not be reached to check the key, or the VM manager failed "
              + "to create the VM; nothing was created.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The VM manager cannot be reached; nothing was created.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<Workspace> create(@Valid @RequestBody CreateWorkspaceRequest request);

  /**
   * Renames a workspace.
   *
   * @param id the workspace
   * @param request the new name, trimmed before it is stored
   * @return the renamed workspace
   */
  @Operation(
      operationId = "renameWorkspace",
      summary = "Rename a workspace",
      description = "Replaces the name. Surrounding whitespace is trimmed.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Workspace.class),
              examples = @ExampleObject(WORKSPACE_EXAMPLE)))
  @ApiResponse(
      responseCode = "400",
      description = "The name is blank or too long, or the id is not a UUID.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(BAD_REQUEST_EXAMPLE)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(NOT_FOUND_EXAMPLE)))
  @PutMapping(path = "/{id}", consumes = JSON, produces = JSON)
  ResponseEntity<Workspace> rename(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID id,
      @Valid @RequestBody WorkspaceRequest request);

  /**
   * Restarts a workspace's runtime.
   *
   * @param id the workspace
   */
  @Operation(
      operationId = "restartWorkspace",
      summary = "Restart a workspace",
      description =
          "Reboots the workspace's VM. Its disk is kept; running processes and open terminals "
              + "are not. It reads its secrets afresh, so restartRequired is cleared. Answers as "
              + "soon as the reboot is under way; follow /workspaces/events for INITIALIZING and "
              + "then READY.")
  @ApiResponse(responseCode = "202", description = "Restarting.")
  @ApiResponse(
      responseCode = "400",
      description = "The id is not a UUID.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(NOT_FOUND_EXAMPLE)))
  @ApiResponse(
      responseCode = "409",
      description = "The workspace has no VM to reboot, as one created before VMs does not.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/{id}/restart")
  ResponseEntity<Void> restart(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID id);

  /**
   * Deletes a workspace.
   *
   * @param id the workspace
   */
  @Operation(
      operationId = "deleteWorkspace",
      summary = "Delete a workspace",
      description =
          "Removes the workspace and stops its runtime, answering only once the runtime is "
              + "gone, which can take a few seconds. If it does not stop in time the workspace "
              + "is kept and the call fails.")
  @ApiResponse(responseCode = "204", description = "Deleted, runtime included.")
  @ApiResponse(
      responseCode = "400",
      description = "The id is not a UUID.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(NOT_FOUND_EXAMPLE)))
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID id);
}
