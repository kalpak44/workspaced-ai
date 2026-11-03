package io.github.kalpak44.workspacedai.api.terminal;

import io.github.kalpak44.workspacedai.api.runtime.WorkspaceTerminal;
import io.github.kalpak44.workspacedai.api.workspace.WorkspaceService;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Bridges one browser WebSocket to one Kubernetes exec TTY. */
@Component
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

  private static final Pattern WORKSPACE = Pattern.compile("/workspaces/([^/]+)/terminal$");
  private static final Pattern RESIZE = Pattern.compile("resize:(\\d+):(\\d+)");
  private static final int MAX_DIMENSION = 1000;

  private final WorkspaceService workspaces;
  private final Map<String, WorkspaceTerminal> terminals = new ConcurrentHashMap<>();

  public TerminalWebSocketHandler(WorkspaceService workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
    var session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1_048_576);
    try {
      var id = workspaceId(session);
      var terminal =
          workspaces.terminal(
              id,
              new SocketOutput(session),
              () -> close(session, CloseStatus.NORMAL.withReason("shell exited")));
      if (session.isOpen()) {
        terminals.put(session.getId(), terminal);
      } else {
        terminal.close();
      }
    } catch (RuntimeException e) {
      close(session, CloseStatus.SERVER_ERROR.withReason(safeReason(e)));
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message)
      throws IOException {
    var terminal = terminals.get(session.getId());
    if (terminal == null) {
      return;
    }
    var payload = message.getPayload();
    var bytes = new byte[payload.remaining()];
    payload.get(bytes);
    terminal.input(bytes);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    var terminal = terminals.get(session.getId());
    var resize = RESIZE.matcher(message.getPayload());
    if (terminal == null || !resize.matches()) {
      return;
    }
    var columns = Integer.parseInt(resize.group(1));
    var rows = Integer.parseInt(resize.group(2));
    if (columns > 0 && rows > 0 && columns <= MAX_DIMENSION && rows <= MAX_DIMENSION) {
      terminal.resize(columns, rows);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    var terminal = terminals.remove(session.getId());
    if (terminal != null) {
      terminal.close();
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    close(session, CloseStatus.SERVER_ERROR);
  }

  private static UUID workspaceId(WebSocketSession session) {
    var uri = session.getUri();
    var match = WORKSPACE.matcher(uri == null ? "" : uri.getPath());
    if (!match.find()) {
      throw new IllegalArgumentException("invalid terminal path");
    }
    return UUID.fromString(match.group(1));
  }

  private static String safeReason(RuntimeException error) {
    var reason = error.getMessage() == null ? "terminal unavailable" : error.getMessage();
    return reason.substring(0, Math.min(reason.length(), 100));
  }

  private static void close(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) {
        session.close(status);
      }
    } catch (IOException ignored) {
      // The connection is already unusable, so there is nothing more to send it.
    }
  }

  private static final class SocketOutput extends OutputStream {
    private final WebSocketSession session;

    private SocketOutput(WebSocketSession session) {
      this.session = session;
    }

    @Override
    public synchronized void write(int value) throws IOException {
      write(new byte[] {(byte) value});
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
      if (session.isOpen()) {
        session.sendMessage(new BinaryMessage(Arrays.copyOfRange(bytes, offset, offset + length)));
      }
    }
  }
}
