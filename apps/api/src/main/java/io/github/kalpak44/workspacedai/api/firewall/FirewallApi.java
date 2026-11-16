package io.github.kalpak44.workspacedai.api.firewall;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Workspace firewall API: the routes and their OpenAPI documentation.
 *
 * @see FirewallController
 */
@Tag(name = "Firewall", description = "Which ports of a workspace's VM the internet may reach")
@RequestMapping("/workspaces/{workspaceId}/firewall")
public interface FirewallApi {

  String JSON = "application/json";
  String EXAMPLE =
      "{\"address\":\"203.0.113.10\",\"agentAllowed\":true,\"rules\":[{\"protocol\":\"tcp\","
          + "\"port\":\"8080\",\"sourceIps\":[\"0.0.0.0/0\",\"::/0\"],\"description\":\"Blog\"}]}";

  @Operation(
      operationId = "getFirewall",
      summary = "Read the workspace's firewall",
      description =
          "The VM's public address and every inbound rule. A new workspace has none, so nothing "
              + "reaches it.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = WorkspaceFirewall.class),
              examples = @ExampleObject(EXAMPLE)))
  @ApiResponse(responseCode = "404", description = "No such workspace.")
  @ApiResponse(responseCode = "409", description = "The workspace has no VM yet.")
  @ApiResponse(responseCode = "502", description = "The VM manager failed.")
  @GetMapping(produces = JSON)
  ResponseEntity<WorkspaceFirewall> get(@PathVariable UUID workspaceId);

  @Operation(
      operationId = "setFirewall",
      summary = "Replace the workspace's firewall rules",
      description =
          "Replaces every rule at once; an empty list closes the VM again. A rule with no "
              + "sourceIps lets in anyone. Whatever listens on an opened port is reachable at the "
              + "address over plain HTTP; TLS is the workspace's own to set up.")
  @ApiResponse(
      responseCode = "200",
      description = "Set; the rules as they are kept.",
      content =
          @Content(
              mediaType = JSON,
              schema = @Schema(implementation = WorkspaceFirewall.class),
              examples = @ExampleObject(EXAMPLE)))
  @ApiResponse(
      responseCode = "400",
      description =
          "A rule names another protocol, a port it cannot have, or a source that is not a "
              + "network, or there are more than 50 rules.")
  @ApiResponse(responseCode = "404", description = "No such workspace.")
  @ApiResponse(responseCode = "409", description = "The workspace has no VM yet.")
  @ApiResponse(responseCode = "502", description = "The VM manager failed.")
  @PutMapping(consumes = JSON, produces = JSON)
  ResponseEntity<WorkspaceFirewall> set(
      @PathVariable UUID workspaceId, @Valid @RequestBody FirewallRulesRequest request);

  @Operation(
      operationId = "setFirewallAgentAccess",
      summary = "Let the workspace's agent open and close ports",
      description =
          "With it on, the agent's firewall tool opens and closes ports as the user can here.")
  @ApiResponse(responseCode = "204", description = "Saved.")
  @ApiResponse(responseCode = "400", description = "allowed is required.")
  @ApiResponse(responseCode = "404", description = "No such workspace.")
  @PutMapping(path = "/agent", consumes = JSON)
  ResponseEntity<Void> setAgentAccess(
      @PathVariable UUID workspaceId, @Valid @RequestBody AgentAccessRequest request);
}
