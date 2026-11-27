package io.github.kalpak44.workspacedai.api.collaborator;

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
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Workspace collaborators: the routes and their OpenAPI documentation.
 *
 * @see CollaboratorsController
 */
@Tag(
    name = "Collaborators",
    description =
        "Who else may use a workspace. Anyone on it invites by email; the invitation's link works"
            + " once, for 24 hours, for whoever signs in with that address. A collaborator may do"
            + " everything but delete the workspace or keep a demo, and the workspace keeps using"
            + " its owner's AI connector. Only the owner removes collaborators. Each user sees only"
            + " the chat sessions they started")
public interface CollaboratorsApi {

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";

  @Operation(
      operationId = "listCollaborators",
      summary = "List who is on a workspace",
      description =
          "The owner, then collaborators as they joined (APPLIED), then invitations not yet"
              + " accepted or expired (PENDING), which show no address.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Collaborator.class))))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace of the user's has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/workspaces/{workspaceId}/collaborators", produces = JSON)
  ResponseEntity<List<Collaborator>> list(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId);

  @Operation(
      operationId = "inviteCollaborator",
      summary = "Invite someone by email",
      description =
          "Mails the address a link that works once, for 24 hours. Inviting the same address"
              + " again sends a new link and the earlier one stops working.")
  @ApiResponse(
      responseCode = "201",
      description = "Sent.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Collaborator.class)))
  @ApiResponse(
      responseCode = "400",
      description = "Not an email address.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description =
          "The workspace is a free demo, which cannot be shared; the address is already on it;"
              + " or its 5 collaborator seats, open invitations included, are taken.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description =
          "That address was invited less than a minute ago, or the caller has sent 50"
              + " invitations in the last 24 hours.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/workspaces/{workspaceId}/invitations", consumes = JSON, produces = JSON)
  ResponseEntity<Collaborator> invite(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Valid @RequestBody InviteRequest request);

  @Operation(
      operationId = "revokeInvitation",
      summary = "Withdraw an open invitation",
      description = "Its link stops working. Only the owner may.")
  @ApiResponse(responseCode = "204", description = "Withdrawn.")
  @ApiResponse(
      responseCode = "403",
      description = "The caller is a collaborator, not the owner.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or no such open invitation into it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
  ResponseEntity<Void> revoke(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "Invitation id") @PathVariable UUID invitationId);

  @Operation(
      operationId = "removeCollaborator",
      summary = "Remove a collaborator",
      description =
          "Only the owner removes others; a collaborator may remove themselves to leave. Their"
              + " open terminals and session streams are closed at once. The sessions they"
              + " started stay in the workspace, and stay on its VM, which everyone on it can"
              + " read from a terminal.")
  @ApiResponse(responseCode = "204", description = "Removed.")
  @ApiResponse(
      responseCode = "403",
      description = "A collaborator tried to remove someone else.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or that user is not a collaborator on it.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "That user is the owner.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/workspaces/{workspaceId}/collaborators/{userId}")
  ResponseEntity<Void> remove(
      @Parameter(description = "Workspace id") @PathVariable UUID workspaceId,
      @Parameter(description = "The collaborator's user id") @PathVariable UUID userId);

  @Operation(
      operationId = "acceptInvitation",
      summary = "Accept an invitation",
      description =
          "Joins the workspace the code opens and uses the invitation up. The caller must be"
              + " signed in with the address it was sent to.")
  @ApiResponse(
      responseCode = "200",
      description = "Joined, or already on the workspace.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = AcceptedInvitation.class)))
  @ApiResponse(
      responseCode = "403",
      description = "The invitation was sent to another address; it still works for that one.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such invitation: wrong, expired, revoked or already used.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The workspace already has 5 collaborators; the invitation still works.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/invitations/accept", consumes = JSON, produces = JSON)
  ResponseEntity<AcceptedInvitation> accept(@Valid @RequestBody AcceptInvitationRequest request);

  @Operation(
      operationId = "acceptInvitationById",
      summary = "Accept an invitation from its notification",
      description =
          "What the in-app notification an existing account gets does, in place of the mailed"
              + " link. It admits only a user signed in with the address it was sent to.")
  @ApiResponse(
      responseCode = "200",
      description = "Joined, or already on the workspace.",
      content =
          @Content(mediaType = JSON, schema = @Schema(implementation = AcceptedInvitation.class)))
  @ApiResponse(
      responseCode = "403",
      description = "The invitation was sent to another address.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such invitation: expired, revoked or already used.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The workspace already has 5 collaborators.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/invitations/{invitationId}/accept", produces = JSON)
  ResponseEntity<AcceptedInvitation> acceptById(
      @Parameter(description = "Invitation id") @PathVariable UUID invitationId);
}
