package io.github.kalpak44.workspacedai.api.webserver;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Web server API: the routes and their OpenAPI documentation.
 *
 * @see WebServerController
 */
@Tag(
    name = "Web server",
    description =
        "nginx inside the workspace, public at https://<name>.<domain>. Off by default; turned"
            + " on, it starts with every start of the workspace and serves whatever its config,"
            + " which the user and the agent edit, points at")
@RequestMapping("/workspaces/{workspaceId}/web-server")
public interface WebServerApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getWebServer",
      summary = "Get a workspace's web server",
      description = "Available even when the workspace is stopped.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = WebServer.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<WebServer> get(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "enableWebServer",
      summary = "Turn a workspace's web server on, or rename it",
      description =
          "Claims the name, starts nginx in the workspace and makes it reachable by that name. A"
              + " workspace that already has a name gives it up for the new one.")
  @ApiResponse(
      responseCode = "200",
      description = "On.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = WebServer.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Not one DNS label of at most 59 characters.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "Another workspace has the name, or it is reserved, or the workspace runs a harness"
              + " from before web servers and needs a restart.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "501",
      description = "This instance offers no web server.",
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
  ResponseEntity<WebServer> enable(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Valid @RequestBody WebServerRequest request);

  @Operation(
      operationId = "setWebServerAgentAccess",
      summary = "Let the workspace's agent change its web server, or stop it",
      description =
          "Allowed, the agent can turn the web server on, off and rename it from inside the"
              + " workspace, which makes whatever it serves public. Off by default. Available even"
              + " when the workspace is stopped.")
  @ApiResponse(
      responseCode = "200",
      description = "Saved.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = WebServer.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "501",
      description = "This instance offers no web server.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(path = "/agent", consumes = JSON, produces = JSON)
  ResponseEntity<WebServer> setAgentAllowed(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Valid @RequestBody AgentAccessRequest request);

  @Operation(
      operationId = "disableWebServer",
      summary = "Turn a workspace's web server off",
      description =
          "Stops nginx and frees the name. The config and the site stay in the"
              + " workspace for the next time.")
  @ApiResponse(responseCode = "204", description = "Off.")
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
  @DeleteMapping
  ResponseEntity<Void> disable(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);
}
