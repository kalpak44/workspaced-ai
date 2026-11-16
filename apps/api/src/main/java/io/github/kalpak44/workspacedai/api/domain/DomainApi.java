package io.github.kalpak44.workspacedai.api.domain;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Workspace domains API: the routes and their OpenAPI documentation.
 *
 * @see DomainController
 */
@Tag(name = "Domains", description = "The user's own domains pointed at a workspace")
@RequestMapping("/workspaces/{workspaceId}/domains")
public interface DomainApi {

  String JSON = "application/json";

  @Operation(
      operationId = "listWorkspaceDomains",
      summary = "List the workspace's domains",
      description =
          "Oldest first, each with the records it needs. A name is ACTIVE once public DNS "
              + "resolves it to the workspace, checked about once a minute.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = WorkspaceDomain.class))))
  @ApiResponse(responseCode = "404", description = "No such workspace.")
  @GetMapping(produces = JSON)
  ResponseEntity<List<WorkspaceDomain>> list(@PathVariable UUID workspaceId);

  @Operation(
      operationId = "attachWorkspaceDomain",
      summary = "Point a domain, or a name under one, at the workspace",
      description =
          "The domain itself gets A and AAAA records to the workspace and www a CNAME; a name "
              + "under it gets a CNAME. When the user's DNS provider applies our template, "
              + "connectUrl is where they approve it; otherwise they add the records themselves. "
              + "Attaching a name the workspace already has answers it again.")
  @ApiResponse(
      responseCode = "201",
      description = "Attached; pending until public DNS points it here.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = WorkspaceDomain.class)))
  @ApiResponse(
      responseCode = "400",
      description =
          "Not a domain, one that does not exist, one of ours, or the workspace has as many as "
              + "it may.")
  @ApiResponse(responseCode = "404", description = "No such workspace.")
  @ApiResponse(responseCode = "409", description = "The workspace has no VM yet.")
  @ApiResponse(
      responseCode = "503",
      description = "DNS could not be checked, or the VM has no name to point at.")
  @PostMapping(consumes = JSON, produces = JSON)
  ResponseEntity<WorkspaceDomain> attach(
      @PathVariable UUID workspaceId, @Valid @RequestBody AttachDomainRequest request);

  @Operation(
      operationId = "detachWorkspaceDomain",
      summary = "Stop pointing a domain at the workspace",
      description =
          "Answers the records it had. They stay at the user's DNS provider, which only the "
              + "user can change, so the user is shown what to remove.")
  @ApiResponse(
      responseCode = "200",
      description = "Detached; the records to remove.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = DnsRecordView.class))))
  @ApiResponse(responseCode = "404", description = "No such workspace or domain.")
  @DeleteMapping(path = "/{domainId}", produces = JSON)
  ResponseEntity<List<DnsRecordView>> detach(
      @PathVariable UUID workspaceId, @PathVariable UUID domainId);
}
