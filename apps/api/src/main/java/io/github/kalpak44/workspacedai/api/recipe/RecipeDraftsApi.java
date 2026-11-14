package io.github.kalpak44.workspacedai.api.recipe;

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
 * Recipe drafts in a workspace: the routes and their OpenAPI documentation.
 *
 * @see RecipeDraftsController
 */
@Tag(
    name = "Recipe drafts",
    description =
        "Recipes a workspace's agent wrote, or is revising, before the user publishes them to the"
            + " store. All need the workspace running")
@RequestMapping("/workspaces/{workspaceId}/recipe-drafts")
public interface RecipeDraftsApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listRecipeDrafts",
      summary = "List a workspace's recipe drafts",
      description = "Every draft, front matter included, in name order.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = RecipeDraft.class))))
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
  ResponseEntity<List<RecipeDraft>> list(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "saveRecipeDraft",
      summary = "Create or replace a recipe draft",
      description =
          "Writes the file whole, e.g. a published recipe for the agent to revise in place.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = RecipeDraft.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is not up to 63 letters, digits, - and _, or the text is missing or longer"
              + " than 100,000 characters.",
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
  ResponseEntity<RecipeDraft> save(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "The file's name, without .md", example = "wordpress") @PathVariable
          String name,
      @Valid @RequestBody RecipeDraftRequest request);

  @Operation(
      operationId = "deleteRecipeDraft",
      summary = "Delete a recipe draft",
      description = "A recipe already published from it is kept.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no recipe draft of that name.",
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
