package io.github.kalpak44.workspacedai.api.instructions;

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
 * Workspace instructions: the routes and their OpenAPI documentation.
 *
 * @see InstructionsController
 */
@Tag(
    name = "Instructions",
    description =
        "Markdown files in a workspace, every one of which its agent is given in every session."
            + " The agent can edit them too. All need the workspace running")
@RequestMapping("/workspaces/{workspaceId}/instructions")
public interface InstructionsApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listInstructions",
      summary = "List a workspace's instructions",
      description =
          "Every instruction file, with its Markdown, in name order, as the workspace holds it:"
              + " the agent's own edits included. A new workspace starts with the harness image's"
              + " defaults.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = InstructionFile.class))))
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
  ResponseEntity<List<InstructionFile>> list(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "saveInstructions",
      summary = "Create or replace an instruction file",
      description =
          "Writes the file whole. A replaced file applies from the agent's next message in every"
              + " session. A new file is given to new sessions at once, and to sessions already"
              + " open once the workspace restarts.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = InstructionFile.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The name is not up to 63 letters, digits, - and _, or the Markdown is missing or"
              + " longer than 100,000 characters.",
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
  ResponseEntity<InstructionFile> save(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "The file's name, without .md", example = "working-style")
          @PathVariable
          String name,
      @Valid @RequestBody InstructionsRequest request);

  @Operation(
      operationId = "deleteInstructions",
      summary = "Delete an instruction file",
      description = "The agent is no longer given it, from its next message in every session.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no instruction file of that name.",
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
