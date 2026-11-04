package io.github.kalpak44.workspacedai.api.terminal;

import io.github.kalpak44.workspacedai.api.harness.TerminalSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "Terminals", description = "Workspace shell sessions, separate from AI conversations.")
@RequestMapping("/workspaces/{workspaceId}/terminals")
@ApiResponse(responseCode = "404", description = "No such workspace or terminal.")
@ApiResponse(responseCode = "503", description = "The workspace's OpenCode service is unavailable.")
public interface TerminalApi {
  record CreateTerminalRequest(@NotBlank @Size(max = 100) String title) {}

  @Operation(operationId = "listTerminals", summary = "List running and exited terminal sessions")
  @GetMapping
  ResponseEntity<List<TerminalSession>> list(@PathVariable UUID workspaceId);

  @Operation(
      operationId = "createTerminal",
      summary = "Create a Bash terminal",
      description =
          "The shell starts in the workspace project. It survives browser disconnections, until explicitly deleted or the workspace runtime restarts.")
  @ApiResponse(
      responseCode = "201",
      description =
          "Created. Attach through the WebSocket at /workspaces/{workspaceId}/terminals/{terminalId}/connect.")
  @PostMapping(consumes = "application/json")
  ResponseEntity<TerminalSession> create(
      @PathVariable UUID workspaceId, @Valid @RequestBody CreateTerminalRequest request);

  @Operation(operationId = "getTerminal", summary = "Get terminal status and exit code")
  @GetMapping("/{terminalId}")
  ResponseEntity<TerminalSession> get(
      @PathVariable UUID workspaceId, @PathVariable String terminalId);

  @Operation(
      operationId = "deleteTerminal",
      summary = "Terminate and remove a terminal session",
      description =
          "Stops the terminal process, leaving workspace files and AI conversations intact. Already removed terminals are ignored.")
  @ApiResponse(responseCode = "204", description = "Removed, or already absent.")
  @DeleteMapping("/{terminalId}")
  ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable String terminalId);
}
