package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceAccessEnded;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalWebSocketHandlerTest {

  @Test
  void bridgesInputOutputResizeAndClose() throws Exception {
    var service = mock(TerminalService.class);
    var terminal = mock(WorkspaceTerminal.class);
    var id = UUID.randomUUID();
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("socket-1");
    when(session.getUri())
        .thenReturn(
            URI.create("ws://localhost/api/v1/workspaces/" + id + "/terminals/pty_1/connect"));
    when(session.isOpen()).thenReturn(true);
    when(session.getPrincipal()).thenReturn(principal(UUID.randomUUID()));
    when(service.connect(eq(id), eq("pty_1"), any(), any())).thenReturn(terminal);
    var handler = new TerminalWebSocketHandler(service, validUsers());

    handler.afterConnectionEstablished(session);
    handler.handleMessage(session, new BinaryMessage("ls\n".getBytes(StandardCharsets.UTF_8)));
    handler.handleMessage(session, new TextMessage("resize:120:40"));
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    verify(terminal).input(aryEq("ls\n".getBytes(StandardCharsets.UTF_8)));
    verify(terminal).resize(120, 40);
    verify(terminal).close();

    var output = ArgumentCaptor.forClass(OutputStream.class);
    verify(service).connect(eq(id), eq("pty_1"), output.capture(), any());
    output.getValue().write("ready".getBytes(StandardCharsets.UTF_8));
    var message = ArgumentCaptor.forClass(BinaryMessage.class);
    verify(session).sendMessage(message.capture());
    assertThat(message.getValue().getPayload().array())
        .isEqualTo("ready".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void cutsOnlyTheConnectionsOfTheUserWhoLostTheWorkspace() throws Exception {
    var service = mock(TerminalService.class);
    when(service.connect(any(), any(), any(), any())).thenReturn(mock(WorkspaceTerminal.class));
    var handler = new TerminalWebSocketHandler(service, validUsers());
    var workspace = UUID.randomUUID();
    var removed = UUID.randomUUID();
    var owner = UUID.randomUUID();
    var hers = socket("socket-1", workspace, removed);
    var his = socket("socket-2", workspace, owner);
    handler.afterConnectionEstablished(hers);
    handler.afterConnectionEstablished(his);

    handler.accessEnded(new WorkspaceAccessEnded(workspace, removed));

    verify(hers).close(any(CloseStatus.class));
    verify(his, never()).close(any(CloseStatus.class));
  }

  @Test
  void rejectsCommandsAfterSignOutOnAnotherInstanceAndKeepsOtherUsersConnected() throws Exception {
    var service = mock(TerminalService.class);
    var terminal = mock(WorkspaceTerminal.class);
    when(service.connect(any(), any(), any(), any())).thenReturn(terminal);
    var users = validUsers();
    var handler = new TerminalWebSocketHandler(service, users);
    var workspace = UUID.randomUUID();
    var user = UUID.randomUUID();
    var revoked = socket("revoked", workspace, user);
    var healthy = socket("healthy", workspace, UUID.randomUUID());
    handler.afterConnectionEstablished(revoked);
    handler.afterConnectionEstablished(healthy);
    var access = mock(UserRepository.Access.class);
    when(access.getSessionsValidAfter()).thenReturn(Instant.now());
    when(users.findAccessById(user)).thenReturn(Optional.of(access));

    handler.handleMessage(
        revoked, new BinaryMessage("rm -rf project".getBytes(StandardCharsets.UTF_8)));
    handler.checkSessions();

    verify(terminal, never()).input(any());
    verify(revoked).close(any(CloseStatus.class));
    verify(healthy, never()).close(any(CloseStatus.class));
  }

  @Test
  void closesIdleSocketsAfterAccountDeletionAndTokenExpiry() throws Exception {
    var service = mock(TerminalService.class);
    when(service.connect(any(), any(), any(), any())).thenReturn(mock(WorkspaceTerminal.class));
    var users = validUsers();
    var handler = new TerminalWebSocketHandler(service, users);
    var user = UUID.randomUUID();
    var deleted = socket("deleted", UUID.randomUUID(), user);
    handler.afterConnectionEstablished(deleted);
    when(users.findAccessById(user)).thenReturn(Optional.empty());
    handler.checkSessions();
    verify(deleted).close(any(CloseStatus.class));

    var expired = socket("expired", UUID.randomUUID(), UUID.randomUUID());
    when(expired.getPrincipal())
        .thenReturn(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("old")
                    .header("alg", "HS256")
                    .subject(user.toString())
                    .issuedAt(Instant.now().minusSeconds(120))
                    .expiresAt(Instant.now().minusSeconds(60))
                    .build()));
    handler.afterConnectionEstablished(expired);
    verify(expired).close(any(CloseStatus.class));
  }

  private static UserRepository validUsers() {
    var users = mock(UserRepository.class);
    var access = mock(UserRepository.Access.class);
    when(access.getSessionsValidAfter()).thenReturn(Instant.EPOCH);
    when(users.findAccessById(any())).thenReturn(Optional.of(access));
    return users;
  }

  private static JwtAuthenticationToken principal(UUID user) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("t")
            .header("alg", "HS256")
            .subject(user.toString())
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());
  }

  private static WebSocketSession socket(String id, UUID workspace, UUID user) {
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.getUri())
        .thenReturn(
            URI.create(
                "ws://localhost/api/v1/workspaces/" + workspace + "/terminals/pty_1/connect"));
    when(session.isOpen()).thenReturn(true);
    when(session.getPrincipal()).thenReturn(principal(user));
    return session;
  }
}
