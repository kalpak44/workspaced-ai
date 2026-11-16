package io.github.kalpak44.workspacedai.vmmanager.vm.web;

import io.github.kalpak44.workspacedai.vmmanager.vm.web.dto.VmFirewallDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * VM firewall API: the routes and their OpenAPI documentation.
 *
 * @see VmFirewallController
 */
@Tag(name = "VM firewalls", description = "What may reach each VM from the internet")
@RequestMapping("/vms/{id}/firewall")
public interface VmFirewallApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";
  String EXAMPLE =
      "{\"rules\":[{\"protocol\":\"tcp\",\"port\":\"8080\","
          + "\"sourceIps\":[\"0.0.0.0/0\",\"::/0\"],\"description\":\"Blog\"}]}";

  @Operation(
      operationId = "getVmFirewall",
      summary = "Read a VM's firewall",
      description = "Its inbound rules. A new VM has none, so nothing reaches it.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = VmFirewallDto.class),
              examples = @ExampleObject(EXAMPLE)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The VM has no firewall of its own.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Hetzner failed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<VmFirewallDto> get(@PathVariable UUID id);

  @Operation(
      operationId = "setVmFirewall",
      summary = "Replace a VM's firewall rules",
      description =
          "Replaces every rule at once; an empty list closes the VM again. A rule with no "
              + "sourceIps lets in anyone, over IPv4 and IPv6. Answers the rules as Hetzner keeps "
              + "them, with the protocol lowercased and every source in CIDR notation.")
  @ApiResponse(
      responseCode = "200",
      description = "Set.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = VmFirewallDto.class),
              examples = @ExampleObject(EXAMPLE)))
  @ApiResponse(
      responseCode = "400",
      description =
          "A rule names another protocol, a port it cannot have, a source that is not a network "
              + "or has host bits set, or there are more than 50 rules.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "There is no such VM.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The VM has no firewall of its own.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "502",
      description = "Hetzner failed.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PutMapping(consumes = JSON, produces = JSON)
  ResponseEntity<VmFirewallDto> set(@PathVariable UUID id, @Valid @RequestBody VmFirewallDto body);
}
