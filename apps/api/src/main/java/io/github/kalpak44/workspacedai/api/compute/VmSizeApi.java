package io.github.kalpak44.workspacedai.api.compute;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The sizes a workspace's VM can be created as: the routes and their OpenAPI documentation.
 *
 * @see VmSizeController
 */
@Tag(name = "VM sizes", description = "What a workspace's VM can be created as")
@RequestMapping("/vm-sizes")
public interface VmSizeApi {

  @Operation(
      operationId = "listVmSizes",
      summary = "List VM sizes",
      description =
          "Every plan a new workspace can be created as, in the order they are offered, with its"
              + " server's specs and its monthly price. An admin sees what the VM costs; any other"
              + " user sees the plan's fixed price. A plan whose price no longer covers its server's"
              + " cost is listed as unavailable.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = "application/json",
              array = @ArraySchema(schema = @Schema(implementation = VmSize.class))))
  @ApiResponse(
      responseCode = "502",
      description = "The VM manager, or Hetzner behind it, failed.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The VM manager cannot be reached, so no workspace can be created now.",
      content =
          @Content(
              mediaType = "application/problem+json",
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = "application/json")
  ResponseEntity<List<VmSize>> list();

  @Operation(
      operationId = "getPlanWaiting",
      summary = "Whether the user waits for a plan",
      description = "True while the user is waiting to be emailed once a plan is back.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = PlanWaiting.class)))
  @GetMapping(path = "/waiting", produces = "application/json")
  ResponseEntity<PlanWaiting> waiting();

  @Operation(
      operationId = "waitForPlan",
      summary = "Email me when a plan is back",
      description =
          "Emails the user once, as soon as a regular plan can be created again, then forgets the"
              + " request. Asking again changes nothing.")
  @ApiResponse(responseCode = "204", description = "Waiting.")
  @PutMapping("/waiting")
  ResponseEntity<Void> waitForPlan();

  @Operation(
      operationId = "stopWaitingForPlan",
      summary = "Stop waiting for a plan",
      description = "Cancels the request; one already answered changes nothing.")
  @ApiResponse(responseCode = "204", description = "Not waiting.")
  @DeleteMapping("/waiting")
  ResponseEntity<Void> stopWaiting();
}
