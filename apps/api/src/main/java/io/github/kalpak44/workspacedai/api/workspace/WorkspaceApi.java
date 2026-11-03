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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

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
      "{\"id\":\"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\",\"name\":\"Research\"}";

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
      description = "Every workspace, in the order they were created.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Workspace.class)),
              examples = @ExampleObject("[" + WORKSPACE_EXAMPLE + "]")))
  @GetMapping(produces = JSON)
  List<Workspace> list();

  /**
   * Creates a workspace.
   *
   * @param request the name, trimmed before it is stored
   * @return the workspace with its new id, and its URL in {@code Location}
   */
  @Operation(
      operationId = "createWorkspace",
      summary = "Create a workspace",
      description = "The server assigns the id. Surrounding whitespace is trimmed from the name.")
  @ApiResponse(
      responseCode = "201",
      description = "Created; its URL is in the Location header.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Workspace.class),
              examples = @ExampleObject(WORKSPACE_EXAMPLE)))
  @ApiResponse(
      responseCode = "400",
      description = "The name is blank or longer than 100 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(BAD_REQUEST_EXAMPLE)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<Workspace> create(@Valid @RequestBody WorkspaceRequest request);

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
  Workspace rename(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID id,
      @Valid @RequestBody WorkspaceRequest request);

  /**
   * Deletes a workspace.
   *
   * @param id the workspace
   */
  @Operation(
      operationId = "deleteWorkspace",
      summary = "Delete a workspace",
      description = "Removes the workspace for good.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
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
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID id);
}
