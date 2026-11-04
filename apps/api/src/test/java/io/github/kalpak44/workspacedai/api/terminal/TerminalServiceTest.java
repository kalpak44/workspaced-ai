package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.kalpak44.workspacedai.api.harness.*;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceRuntime;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceRepository;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TerminalServiceTest {
  final WorkspaceRepository repository = mock(WorkspaceRepository.class);
  final WorkspaceRuntime runtime = mock(WorkspaceRuntime.class);
  final Harness harness = mock(Harness.class);
  final TerminalService service = new TerminalService(repository, runtime, harness);
  final UUID workspace = UUID.randomUUID();
  final HarnessEndpoint endpoint = new HarnessEndpoint(URI.create("http://localhost:4096"), "pw");

  @Test
  void rejectsMissingWorkspaceAndUnavailableRuntimeBeforeCallingOpenCode() {
    assertThatThrownBy(() -> service.list(workspace))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(404));
    when(repository.existsById(workspace)).thenReturn(true);
    when(runtime.harness(workspace)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(workspace, "Terminal 1"))
        .isInstanceOf(HarnessUnavailableException.class);
    verifyNoInteractions(harness);
  }

  @Test
  void connectsOnlyToRunningTerminalsInsideTheSelectedWorkspace() {
    when(repository.existsById(workspace)).thenReturn(true);
    when(runtime.harness(workspace)).thenReturn(Optional.of(endpoint));
    when(harness.terminalInfo(endpoint, Harness.PROJECT_ROOT, "pty_1"))
        .thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.connect(workspace, "pty_1", new ByteArrayOutputStream(), () -> {}))
        .isInstanceOf(ResponseStatusException.class);
    var exited =
        new TerminalSession("pty_1", "Shell", "/bin/bash", Harness.PROJECT_ROOT, "exited", 123, 0);
    when(harness.terminalInfo(endpoint, Harness.PROJECT_ROOT, "pty_1"))
        .thenReturn(Optional.of(exited));
    assertThatThrownBy(
            () -> service.connect(workspace, "pty_1", new ByteArrayOutputStream(), () -> {}))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(409));
    var running =
        new TerminalSession(
            "pty_1", "Shell", "/bin/bash", Harness.PROJECT_ROOT, "running", 123, null);
    when(harness.terminalInfo(endpoint, Harness.PROJECT_ROOT, "pty_1"))
        .thenReturn(Optional.of(running));
    service.connect(workspace, "pty_1", new ByteArrayOutputStream(), () -> {});
    verify(harness)
        .connectTerminal(eq(endpoint), eq(Harness.PROJECT_ROOT), eq("pty_1"), any(), any());
    assertThatThrownBy(() -> service.delete(workspace, "../other"))
        .isInstanceOf(ResponseStatusException.class);
    verify(harness, never()).deleteTerminal(any(), any(), any());
  }
}
