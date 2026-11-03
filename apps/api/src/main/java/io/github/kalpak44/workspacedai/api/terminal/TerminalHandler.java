package io.github.kalpak44.workspacedai.api.terminal;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Behaves like a PTY on the wire: raw keystrokes in, raw terminal bytes out, with the echo coming
 * from the server rather than the client. Nothing is executed; a line is answered with a fixed
 * reply until a real PTY sits behind this.
 */
public class TerminalHandler extends TextWebSocketHandler {

  static final String PROMPT = "$ ";

  private final Map<String, StringBuilder> lines = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
    lines.put(socket.getId(), new StringBuilder());
    socket.sendMessage(new TextMessage("workspaced-ai demo terminal\r\n" + PROMPT));
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message)
      throws IOException {
    var line = lines.get(socket.getId());
    var out = new StringBuilder();
    for (char c : message.getPayload().toCharArray()) {
      switch (Character.valueOf(c)) {
        case Character ch when ch == '\r' || ch == '\n' -> {
          out.append("\r\n").append(reply(line.toString())).append(PROMPT);
          line.setLength(0);
        }
        // Backspace and DEL: erase one cell, the way a line discipline would.
        case Character ch when (ch == '\b' || ch == 0x7f) && !line.isEmpty() -> {
          line.setLength(line.length() - 1);
          out.append("\b \b");
        }
        case Character ch when ch == '\b' || ch == 0x7f -> {
          // Nothing to erase at the start of a line.
        }
        default -> {
          line.append(c);
          out.append(c);
        }
      }
    }
    socket.sendMessage(new TextMessage(out.toString()));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    lines.remove(socket.getId());
  }

  private static String reply(String line) {
    var command = line.strip();
    return command.isEmpty() ? "" : "demo: " + command + ": not executed\r\n";
  }
}
