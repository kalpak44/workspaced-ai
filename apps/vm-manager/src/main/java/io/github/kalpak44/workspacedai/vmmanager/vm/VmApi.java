package io.github.kalpak44.workspacedai.vmmanager.vm;

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
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * VM API: the routes and their OpenAPI documentation, kept apart from the implementation so the
 * controller reads as behaviour only.
 *
 * @see VmController
 */
@Tag(name = "VMs", description = "The Hetzner VMs workspaces run on")
@RequestMapping("/vms")
public interface VmApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  String VM_EXAMPLE =
      "{\"id\":\"8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21\","
          + "\"ownerRef\":\"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\",\"serverType\":\"cx23\","
          + "\"status\":\"PROVISIONING\",\"ipv4\":null,"
          + "\"createdAt\":\"2026-11-14T10:00:00Z\",\"lastSeenAt\":null}";

  String NOT_FOUND_EXAMPLE =
      "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
          + "\"detail\":\"no VM 8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21\","
          + "\"instance\":\"/api/v1/vms/8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21\"}";

  @Operation(
      operationId = "listVms",
      summary = "List VMs",
      description = "Every VM, oldest first; only one owner's when ownerRef is given.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Vm.class)),
              examples = @ExampleObject("[" + VM_EXAMPLE + "]")))
  @GetMapping(produces = JSON)
  ResponseEntity<List<Vm>> list(
      @Parameter(description = "Only this owner's VMs") @RequestParam Optional<String> ownerRef);

  @Operation(
      operationId = "streamVmEvents",
      summary = "Stream VM changes",
      description =
          "Server-sent events. On connect, one `status` event per existing VM; after that, one "
              + "each time a VM changes, and a `deleted` event (`{\"id\":…}`) for each deletion. "
              + "A `keepalive` comment is sent every 30 seconds.")
  @ApiResponse(
      responseCode = "200",
      description = "An open event stream.",
      content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> events();

  @Operation(operationId = "getVm", summary = "Get a VM", description = "One VM by its id.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Vm.class),
              examples = @ExampleObject(VM_EXAMPLE)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(NOT_FOUND_EXAMPLE)))
  @GetMapping(path = "/{id}", produces = JSON)
  ResponseEntity<Vm> get(@PathVariable UUID id);

  @Operation(
      operationId = "createVm",
      summary = "Create a VM",
      description =
          "Requests a VM for the owner and returns at once; it is PROVISIONING until it reports "
              + "ready. An owner has at most one VM: asking again returns the one it has, with "
              + "200 instead of 201, so a retried request never creates a second.")
  @ApiResponse(
      responseCode = "201",
      description = "Created; its URL is in the Location header.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = Vm.class),
              examples = @ExampleObject(VM_EXAMPLE)))
  @ApiResponse(
      responseCode = "200",
      description = "The owner already had this VM.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Vm.class)))
  @ApiResponse(
      responseCode = "400",
      description = "The ownerRef is blank or too long, or the serverType is not offered.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<Vm> create(@Valid @RequestBody CreateVmRequest request);

  @Operation(
      operationId = "deleteVm",
      summary = "Delete a VM",
      description = "Deletes the VM and its disk.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(NOT_FOUND_EXAMPLE)))
  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id);

  @Operation(
      operationId = "restartVm",
      summary = "Restart a VM",
      description = "Reboots it, keeping its disk; it is RESTARTING until it reports ready.")
  @ApiResponse(
      responseCode = "202",
      description = "Restarting.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Vm.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{id}/restart", produces = JSON)
  ResponseEntity<Vm> restart(@PathVariable UUID id);

  @Operation(
      operationId = "reportVmReady",
      summary = "Report a VM ready",
      description =
          "Once the VM has booted; it becomes READY. The VM reports it to the workspace API, "
              + "which relays it here: this service is internal to the cluster.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Vm.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{id}/ready", produces = JSON)
  ResponseEntity<Vm> ready(@PathVariable UUID id);

  @Operation(
      operationId = "reportVmHeartbeat",
      summary = "Report a VM alive",
      description =
          "Periodically while the VM runs, relayed by the workspace API; refreshes lastSeenAt "
              + "only.")
  @ApiResponse(responseCode = "204", description = "Recorded.")
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/{id}/heartbeat")
  ResponseEntity<Void> heartbeat(@PathVariable UUID id);
}
