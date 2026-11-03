package io.github.kalpak44.workspacedai.api.settings;

import io.github.kalpak44.workspacedai.api.harness.ModelCatalogue;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Agent settings API: the routes and their OpenAPI documentation.
 *
 * @see SettingsController
 */
@Tag(name = "Agent settings", description = "The model and MCP servers a workspace's agent uses")
@RequestMapping("/workspaces/{workspaceId}")
public interface SettingsApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";
  String WORKSPACE_ID = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11";

  @Operation(
      operationId = "listModels",
      summary = "List the models the agent can use",
      description =
          "The workspace provider's models that can call tools, as the harness's catalogue has "
              + "them, sorted by name, with the one used when the workspace names none.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = ModelCatalogue.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace's harness is not running or not answering.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/models", produces = JSON)
  ResponseEntity<ModelCatalogue> models(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId);

  @Operation(
      operationId = "setModel",
      summary = "Choose the agent's model",
      description = "Applies from the next message in every session. Null returns to the default.")
  @ApiResponse(
      responseCode = "200",
      description = "Chosen.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = ModelRequest.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The provider offers no such agent model.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The harness, which holds the catalogue, is not running.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/model", consumes = JSON, produces = JSON)
  ResponseEntity<ModelRequest> setModel(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Valid @RequestBody ModelRequest request);

  @Operation(
      operationId = "listMcpServers",
      summary = "List the agent's MCP servers",
      description = "With each one's connection status as the harness reports it.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = McpServer.class))))
  @GetMapping(path = "/mcp-servers", produces = JSON)
  ResponseEntity<List<McpServer>> mcpServers(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId);

  @Operation(
      operationId = "addMcpServer",
      summary = "Add an MCP server",
      description =
          "Every session can use it at once, and a restarted workspace gets it back. Reference a "
              + "workspace secret in a header as {env:NAME}.")
  @ApiResponse(
      responseCode = "201",
      description = "Added.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = McpServer.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The name, URL or a header is not valid.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The workspace already has an MCP server of that name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/mcp-servers", consumes = JSON, produces = JSON)
  ResponseEntity<McpServer> addMcpServer(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Valid @RequestBody McpServerRequest request);

  @Operation(operationId = "removeMcpServer", summary = "Remove an MCP server")
  @ApiResponse(responseCode = "204", description = "Removed; the agent stops using it at once.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no MCP server of that name.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/mcp-servers/{name}")
  ResponseEntity<Void> removeMcpServer(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Server name", example = "github") @PathVariable String name);
}
