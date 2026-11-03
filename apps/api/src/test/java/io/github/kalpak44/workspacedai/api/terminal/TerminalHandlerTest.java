package io.github.kalpak44.workspacedai.api.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.class)
class TerminalHandlerTest {

  @LocalServerPort int port;

  final BlockingQueue<String> received = new LinkedBlockingQueue<>();
  WebSocketSession socket;

  @BeforeEach
  void connect() throws Exception {
    var handler =
        new TextWebSocketHandler() {
          @Override
          protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
          }
        };
    socket =
        new StandardWebSocketClient()
            .execute(handler, "ws://localhost:" + port + "/api/v1/sessions/any/terminal")
            .get(5, TimeUnit.SECONDS);
  }

  @AfterEach
  void close() throws Exception {
    socket.close();
  }

  @Test
  void greetsWithAPrompt() throws Exception {
    assertThat(receive()).endsWith(TerminalHandler.PROMPT);
  }

  @Test
  void echoesKeystrokesAndAnswersALine() throws Exception {
    receive();

    socket.sendMessage(new TextMessage("ls"));
    assertThat(receive()).isEqualTo("ls");

    socket.sendMessage(new TextMessage("\r"));
    assertThat(receive()).isEqualTo("\r\ndemo: ls: not executed\r\n$ ");
  }

  @Test
  void erasesOnBackspaceButNotPastTheLineStart() throws Exception {
    receive();

    socket.sendMessage(new TextMessage("ab\u007f\u007f\u007f\r"));
    assertThat(receive()).isEqualTo("ab\b \b\b \b\r\n$ ");
  }

  private String receive() throws InterruptedException {
    var message = received.poll(5, TimeUnit.SECONDS);
    assertThat(message).as("terminal output").isNotNull();
    return message;
  }
}
