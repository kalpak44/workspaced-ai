package io.github.kalpak44.workspacedai.api.session;

import io.github.kalpak44.workspacedai.api.harness.AgentForm;
import io.github.kalpak44.workspacedai.api.harness.ChatMessage;
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
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat session API: the routes and their OpenAPI documentation.
 *
 * @see SessionController
 */
@Tag(name = "Sessions", description = "Chats with a workspace's agent in its shared project")
@RequestMapping("/workspaces/{workspaceId}/sessions")
public interface SessionApi {

  @Operation(
      operationId = "sessionAgents",
      summary = "Get the current agent and selectable agents",
      description =
          "Discovers agents at this session's location. Hidden and subagent-only agents are excluded.")
  @ApiResponse(responseCode = "404", description = "No such session in this workspace.")
  @ApiResponse(responseCode = "503", description = "OpenCode is unavailable.")
  @GetMapping(path = "/{sessionId}/agent", produces = JSON)
  ResponseEntity<AgentSelection> agents(
      @PathVariable UUID workspaceId, @PathVariable UUID sessionId);

  @Operation(
      operationId = "switchSessionAgent",
      summary = "Switch the agent for subsequent turns",
      description =
          "Preserves conversation history and the selected model. Only visible primary or all-mode agents may be selected.")
  @ApiResponse(
      responseCode = "400",
      description = "Unknown, hidden, subagent-only, or invalid agent.")
  @ApiResponse(responseCode = "404", description = "No such session in this workspace.")
  @ApiResponse(responseCode = "503", description = "OpenCode is unavailable or refused the switch.")
  @PutMapping(path = "/{sessionId}/agent", consumes = JSON, produces = JSON)
  ResponseEntity<AgentSelection> setAgent(
      @PathVariable UUID workspaceId,
      @PathVariable UUID sessionId,
      @Valid @RequestBody AgentRequest request);

  @Operation(summary = "List questions awaiting an answer from the user")
  @GetMapping(path = "/{sessionId}/forms", produces = JSON)
  ResponseEntity<List<AgentForm>> forms(
      @PathVariable UUID workspaceId, @PathVariable UUID sessionId);

  @Operation(summary = "Answer an agent question and allow execution to continue")
  @PostMapping(path = "/{sessionId}/forms/{formId}/reply", consumes = JSON)
  ResponseEntity<Void> replyForm(
      @PathVariable UUID workspaceId,
      @PathVariable UUID sessionId,
      @PathVariable String formId,
      @Valid @RequestBody FormReply request);

  @Operation(summary = "Dismiss an agent question")
  @DeleteMapping(path = "/{sessionId}/forms/{formId}")
  ResponseEntity<Void> cancelForm(
      @PathVariable UUID workspaceId, @PathVariable UUID sessionId, @PathVariable String formId);

  String JSON = "application/json";
  String PROBLEM_JSON = "application/problem+json";
  String WORKSPACE_ID = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11";
  String SESSION_ID = "8b0e4f7c-1d2a-4c3b-9e5f-6a7b8c9d0e1f";

  @Operation(
      operationId = "listSessions",
      summary = "List a workspace's sessions",
      description = "Oldest first.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = Session.class))))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(produces = JSON)
  ResponseEntity<List<Session>> list(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId);

  @Operation(
      operationId = "createSession",
      summary = "Start a session",
      description =
          "Creates an OpenCode V2 session in the workspace's shared project. "
              + "Its title stays null until the agent names it after the first exchange.")
  @ApiResponse(
      responseCode = "201",
      description = "Created; its URL is in the Location header.",
      content = @Content(mediaType = JSON, schema = @Schema(implementation = Session.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No workspace has that id.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace's harness is not running or not answering.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(produces = JSON)
  ResponseEntity<Session> create(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId);

  @Operation(
      operationId = "deleteSession",
      summary = "Delete a session",
      description = "Removes the session and conversation, retaining shared project files.")
  @ApiResponse(responseCode = "204", description = "Deleted.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no such session.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @DeleteMapping("/{sessionId}")
  ResponseEntity<Void> delete(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Session id", example = SESSION_ID) @PathVariable UUID sessionId);

  @Operation(
      operationId = "listMessages",
      summary = "Read a session's conversation",
      description = "Every message so far, oldest first. Follow new ones on /events.")
  @ApiResponse(
      responseCode = "200",
      description = "OK.",
      content =
          @Content(
              mediaType = JSON,
              array = @ArraySchema(schema = @Schema(implementation = ChatMessage.class))))
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no such session.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace's harness is not running or not answering.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping(path = "/{sessionId}/messages", produces = JSON)
  ResponseEntity<List<ChatMessage>> messages(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Session id", example = SESSION_ID) @PathVariable UUID sessionId);

  @Operation(
      operationId = "sendMessage",
      summary = "Send a message",
      description =
          "Starts the agent's reply and answers at once. The message itself and the reply "
              + "arrive on /events.")
  @ApiResponse(responseCode = "202", description = "Accepted; the reply streams on /events.")
  @ApiResponse(
      responseCode = "400",
      description = "The text is blank or too long.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no such session.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description = "The workspace's harness is not running or not answering.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping(path = "/{sessionId}/messages", consumes = JSON)
  ResponseEntity<Void> send(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Session id", example = SESSION_ID) @PathVariable UUID sessionId,
      @Valid @RequestBody MessageRequest request);

  @Operation(
      operationId = "editMessage",
      summary = "Edit a user message and regenerate",
      description =
          "Replaces this message and all later messages with a new turn. Project files are retained. The session must be idle.")
  @ApiResponse(responseCode = "202", description = "Accepted; the new reply streams on /events.")
  @ApiResponse(responseCode = "400", description = "Invalid text or an assistant message.")
  @ApiResponse(responseCode = "404", description = "No such session or message.")
  @ApiResponse(
      responseCode = "503",
      description = "The harness is unavailable or refused the edit.")
  @PutMapping(path = "/{sessionId}/messages/{messageId}", consumes = JSON)
  ResponseEntity<Void> edit(
      @PathVariable UUID workspaceId,
      @PathVariable UUID sessionId,
      @PathVariable String messageId,
      @Valid @RequestBody MessageRequest request);

  @Operation(
      operationId = "abortSession",
      summary = "Stop the reply in progress",
      description = "Does nothing when the agent is idle.")
  @ApiResponse(responseCode = "204", description = "Stopped, or nothing was running.")
  @ApiResponse(
      responseCode = "404",
      description = "No such workspace, or it has no such session.",
      content =
          @Content(
              mediaType = PROBLEM_JSON,
              schema = @Schema(implementation = ProblemDetail.class)))
  @PostMapping("/{sessionId}/abort")
  ResponseEntity<Void> abort(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Session id", example = SESSION_ID) @PathVariable UUID sessionId);

  @Operation(
      operationId = "streamSessionEvents",
      summary = "Stream a session's conversation",
      description =
          "Server-sent events for this session only: `message` ({id, role}) when a message "
              + "starts; `part` ({messageId, part}) with a part's whole current state, which "
              + "replaces any earlier one of that id; `delta` ({messageId, partId, delta}) with "
              + "text appended to a part; `status` ({busy}); `title` ({title}); `error` "
              + "({message}); `forms` ({}) when pending questions change (reload /forms). "
              + "A `keepalive` comment is sent about every 10 seconds.")
  @ApiResponse(
      responseCode = "200",
      description = "An open event stream.",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              examples =
                  @ExampleObject(
                      """
                      event:delta
                      data:{"messageId":"msg_1","partId":"prt_1","delta":"Hello"}

                      """)))
  @GetMapping(path = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  ResponseEntity<SseEmitter> events(
      @Parameter(description = "Workspace id", example = WORKSPACE_ID) @PathVariable
          UUID workspaceId,
      @Parameter(description = "Session id", example = SESSION_ID) @PathVariable UUID sessionId);
}
