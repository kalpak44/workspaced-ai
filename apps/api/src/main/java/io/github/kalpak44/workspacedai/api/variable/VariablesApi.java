package io.github.kalpak44.workspacedai.api.variable;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Workspace variables: the routes and their OpenAPI documentation.
 *
 * @see VariablesController
 */
@Tag(
    name = "Variables",
    description =
        "Environment variables a workspace starts with, kept as a .env file in it, which its agent"
            + " can edit too. Unlike secrets, their values can be read back. A change applies when"
            + " the workspace next starts, and a secret of the same name wins. All need the"
            + " workspace running")
@RequestMapping("/workspaces/{workspaceId}/variables")
public interface VariablesApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listVariables",
      summary = "List a workspace's variables",
      description = "Every variable in its .env file, in the file's order.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Variable.class))))
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
  ResponseEntity<List<Variable>> list(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "saveVariables",
      summary = "Replace a workspace's variables",
      description =
          "Writes the .env file whole, in the order given. It applies when the workspace next"
              + " starts.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Variable.class))))
  @ApiResponse(
      responseCode = "400",
      description =
          "A name is not an environment variable name, is given twice or starts with OPENCODE_;"
              + " a value is longer than 4,096 characters or has a line break; or there are more"
              + " than 200.",
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
  @PutMapping(consumes = JSON, produces = JSON)
  ResponseEntity<List<Variable>> save(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @NotNull @RequestBody List<@Valid Variable> variables);
}
