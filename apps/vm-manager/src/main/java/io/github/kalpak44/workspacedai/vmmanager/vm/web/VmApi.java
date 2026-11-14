package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.CreateVmDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.HarnessDto;
import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmDto;
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
          + "\"workspaceId\":\"3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11\","
          + "\"userId\":\"00000000-0000-0000-0000-000000000001\",\"serverType\":\"cx23\","
          + "\"serverId\":112233445,\"status\":\"PROVISIONING\",\"ipv4\":\"203.0.113.10\","
          + "\"createdAt\":\"2026-11-14T10:00:00Z\",\"lastSeenAt\":null}";

  String NOT_FOUND_EXAMPLE =
      "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
          + "\"detail\":\"no VM 8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21\","
          + "\"instance\":\"/api/v1/vms/8d1e4b0a-2f6c-4f7e-9b1d-0c3a5e7f9a21\"}";

  @Operation(
      operationId = "listVms",
      summary = "List VMs",
      description = "Every VM, oldest first; only one workspace's when workspaceId is given.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = VmDto.class)),
              examples = @ExampleObject("[" + VM_EXAMPLE + "]")))
  @GetMapping(produces = JSON)
  ResponseEntity<List<VmDto>> list(
      @Parameter(description = "Only this workspace's VM") @RequestParam
          Optional<UUID> workspaceId);

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
              schema = @Schema(implementation = VmDto.class),
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
  ResponseEntity<VmDto> get(@PathVariable UUID id);

  @Operation(
      operationId = "createVm",
      summary = "Create a VM",
      description =
          "Creates a Hetzner server for the workspace and returns once Hetzner has accepted it; it is "
              + "PROVISIONING until it reports ready. A workspace has at most one VM: asking again "
              + "returns the one it has, with 200 instead of 201, so a retried request never "
              + "creates a second.")
  @ApiResponse(
      responseCode = "201",
      description = "Created; its URL is in the Location header.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = VmDto.class),
              examples = @ExampleObject(VM_EXAMPLE)))
  @ApiResponse(
      responseCode = "200",
      description = "The workspace already had this VM.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = VmDto.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "The workspaceId or userId is missing or not a UUID, or the serverType is not offered.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Hetzner refused or failed the create; nothing was kept.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description =
          "No Hetzner token is configured, or the VMs already at Hetzner are not loaded yet.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<VmDto> create(@Valid @RequestBody CreateVmDto body);

  @Operation(
      operationId = "deleteVm",
      summary = "Delete a VM",
      description =
          "Deletes its Hetzner server, disk included, then the VM. A server already gone at "
              + "Hetzner counts as deleted.")
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
      content = @Content(mediaType = JSON, schema = @Schema(implementation = VmDto.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{id}/restart", produces = JSON)
  ResponseEntity<VmDto> restart(@PathVariable UUID id);

  @Operation(
      operationId = "reportVmReady",
      summary = "Report a VM ready",
      description =
          "Once the VM has booted; it becomes READY. The VM reports it to the workspace API, "
              + "which relays it here: this service is internal to the cluster.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = VmDto.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{id}/ready", produces = JSON)
  ResponseEntity<VmDto> ready(@PathVariable UUID id);

  @Operation(
      operationId = "reportVmHeartbeat",
      summary = "Report a VM alive",
      description =
          "Periodically while the VM runs, relayed by the agent hub; refreshes lastSeenAt only. "
              + "Answers the VM so the VM learns its status, RESTARTING among them.")
  @ApiResponse(
      responseCode = "200",
      description = "Recorded.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = VmDto.class),
              examples = @ExampleObject(VM_EXAMPLE)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{id}/heartbeat", produces = JSON)
  ResponseEntity<VmDto> heartbeat(@PathVariable UUID id);

  @Operation(
      operationId = "getVmHarness",
      summary = "Get a VM's harness",
      description =
          "The image the VM's supervisor runs and the environment it starts it with, secret values "
              + "included: the only route that returns them, called only by the agent hub. A VM "
              + "created without a harness environment is given an OPENCODE_PASSWORD here, "
              + "kept from then on.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = HarnessDto.class),
              examples =
                  @ExampleObject(
                      "{\"image\":\"ghcr.io/kalpak44/workspaced-ai-harness:85c2bd5\","
                          + "\"environment\":{\"OPENROUTER_API_KEY\":\"sk-or-v1-…\","
                          + "\"OPENCODE_PASSWORD\":\"…\"}}")))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Vault failed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "No harness image or no Vault address is configured.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/{id}/harness", produces = JSON)
  ResponseEntity<HarnessDto> harness(@PathVariable UUID id);
}
