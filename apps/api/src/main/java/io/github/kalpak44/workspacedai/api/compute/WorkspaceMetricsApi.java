package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * How hard a workspace's VM is working: the routes and their OpenAPI documentation.
 *
 * @see WorkspaceMetricsController
 */
@Tag(name = "Workspace metrics", description = "CPU, memory, disk and network of a workspace's VM")
@RequestMapping("/workspaces/{workspaceId}/metrics")
public interface WorkspaceMetricsApi {

  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "getWorkspaceMetrics",
      summary = "Get a workspace's metrics",
      description =
          "CPU, disk and network over the range as Hetzner reports them, and the VM's memory as "
              + "its last heartbeat reported it. Samples a minute apart up to the last hour, "
              + "wider beyond it.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = WorkspaceMetrics.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The range is not one offered.",
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
      description = "The workspace has no VM yet.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "The VM manager, or Hetzner behind it, failed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The VM manager cannot be reached.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = "application/json")
  ResponseEntity<WorkspaceMetrics> get(
      @Parameter(description = "Workspace id", example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
          @PathVariable
          UUID workspaceId,
      @Parameter(
              description = "How far back: live (15 minutes), 1h, 24h, 7d or 30d",
              example = "live")
          @RequestParam(defaultValue = "live")
          String range);
}
