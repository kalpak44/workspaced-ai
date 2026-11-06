package io.github.kalpak44.workspacedai.api.instructions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * AI instructions: the routes and their OpenAPI documentation.
 *
 * @see InstructionsController
 */
@Tag(
    name = "AI instructions",
    description =
        "What the agent is told in every session: the workspace guide from the harness image, then"
            + " the user's general instructions, then the workspace's own")
public interface InstructionsApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getGeneralInstructions",
      summary = "Get the general instructions",
      description = "The signed-in user's instructions, given in every one of their workspaces.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = GeneralInstructions.class)))
  @GetMapping(path = "/account/instructions", produces = JSON)
  ResponseEntity<GeneralInstructions> general();

  @Operation(
      operationId = "setGeneralInstructions",
      summary = "Replace the general instructions",
      description =
          "Saved at once. Every running workspace of the user's gets them within moments; one that"
              + " is not running gets them when it next starts.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = GeneralInstructions.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Missing, or longer than 100,000 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/account/instructions", consumes = JSON, produces = JSON)
  ResponseEntity<GeneralInstructions> setGeneral(@Valid @RequestBody InstructionsRequest request);

  @Operation(
      operationId = "getWorkspaceInstructions",
      summary = "Get a workspace's instructions",
      description =
          "The workspace's own instructions, and the read-only guide its harness gives the agent"
              + " first, which is null while the workspace is not running.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = WorkspaceInstructions.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/workspaces/{workspaceId}/instructions", produces = JSON)
  ResponseEntity<WorkspaceInstructions> workspace(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "setWorkspaceInstructions",
      summary = "Replace a workspace's instructions",
      description =
          "Saved, then written to the running workspace before the call returns; applied says"
              + " whether that happened. A workspace that is not running gets them when it next"
              + " starts. Writing reloads the workspace's agent, which cancels a question it is"
              + " waiting on.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = SavedInstructions.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Missing, or longer than 100,000 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/workspaces/{workspaceId}/instructions", consumes = JSON, produces = JSON)
  ResponseEntity<SavedInstructions> setWorkspace(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Valid @RequestBody InstructionsRequest request);
}
