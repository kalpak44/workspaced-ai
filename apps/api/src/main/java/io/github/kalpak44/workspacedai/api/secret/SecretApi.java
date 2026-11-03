package io.github.kalpak44.workspacedai.api.secret;

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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Workspace secret API: the routes and their OpenAPI documentation.
 *
 * @see SecretController
 */
@Tag(name = "Workspace secrets", description = "Environment variables a workspace keeps secret")
@RequestMapping("/workspaces/{workspaceId}/secrets")
public interface SecretApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";
  String WORKSPACE_ID = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11";

  /**
   * Lists a workspace's secrets, names only.
   *
   * @param workspaceId the workspace
   * @return the provider's key first, then the rest in the order they were added
   */
  @Operation(
      operationId = "listWorkspaceSecrets",
      summary = "List a workspace's secrets",
      description =
          "Names only; values are never returned. The model provider's key comes first, "
              + "marked managed.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Secret.class)),
              examples =
                  @ExampleObject(
                      "[{\"name\":\"OPENROUTER_API_KEY\",\"managed\":true},"
                          + "{\"name\":\"GITHUB_TOKEN\",\"managed\":false}]")))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<List<Secret>> list(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId);

  /**
   * Adds a secret.
   *
   * @param workspaceId the workspace
   * @param request its name and value
   * @return the secret, without its value
   */
  @Operation(
      operationId = "addWorkspaceSecret",
      summary = "Add a secret to a workspace",
      description =
          "The value goes to the workspace's runtime only. A running workspace sees it once "
              + "it restarts.")
  @ApiResponse(
      responseCode = "201",
      description = "Added.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Secret.class),
              examples = @ExampleObject("{\"name\":\"GITHUB_TOKEN\",\"managed\":false}")))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is not a valid environment variable name or is too long, or the value is "
              + "empty or too long.",
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
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The workspace already has that secret, or its model provider sets it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<Secret> add(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Valid @RequestBody SecretRequest request);

  /**
   * Removes a secret.
   *
   * @param workspaceId the workspace
   * @param name the secret
   */
  @Operation(
      operationId = "removeWorkspaceSecret",
      summary = "Remove a secret from a workspace",
      description = "The model provider's key cannot be removed.")
  @ApiResponse(responseCode = "204", description = "Removed.")
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id, or it has no secret of that name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The secret is set by the workspace's model provider.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{name}")
  ResponseEntity<Void> remove(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Secret name", example = "GITHUB_TOKEN") @PathVariable String name);
}
