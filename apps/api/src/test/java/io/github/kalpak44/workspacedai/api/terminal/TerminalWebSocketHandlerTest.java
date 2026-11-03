package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalWebSocketHandlerTest {

  @Test
  void bridgesInputOutputResizeAndClose() throws Exception {
    var service = mock(WorkspaceService.class);
    var terminal = mock(WorkspaceTerminal.class);
    var id = UUID.randomUUID();
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("socket-1");
    when(session.getUri())
        .thenReturn(URI.create("ws://localhost/api/v1/workspaces/" + id + "/terminal"));
    when(session.isOpen()).thenReturn(true);
    when(service.terminal(eq(id), any(), any())).thenReturn(terminal);
    var handler = new TerminalWebSocketHandler(service);

    handler.afterConnectionEstablished(session);
    handler.handleMessage(session, new BinaryMessage("ls\n".getBytes(StandardCharsets.UTF_8)));
    handler.handleMessage(session, new TextMessage("resize:120:40"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    verify(terminal).input(aryEq("ls\n".getBytes(StandardCharsets.UTF_8)));
    verify(terminal).resize(120, 40);
    verify(terminal).close();

    var output = ArgumentCaptor.forClass(OutputStream.class);
    verify(service).terminal(eq(id), output.capture(), any());
    output.getValue().write("ready".getBytes(StandardCharsets.UTF_8));
    var message = ArgumentCaptor.forClass(BinaryMessage.class);
    verify(session).sendMessage(message.capture());
    assertThat(message.getValue().getPayload().array())
        .isEqualTo("ready".getBytes(StandardCharsets.UTF_8));
  }
}
