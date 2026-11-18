package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Address;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A local server answers as Mailjet's Send API does. */
class MailjetMailerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Mail MAIL =
      new Mail(
          new Address("support@workspaced.online", "workspaced support"),
          List.of(Address.of("ada@example.com")),
          Address.of("sales@workspaced.online"),
          "Hello",
          "Hi **Ada**",
          "<p>Hi <strong>Ada</strong></p>");

  final List<String> bodies = new CopyOnWriteArrayList<>();
  final List<String> authorizations = new CopyOnWriteArrayList<>();
  HttpServer server;
  volatile int status;
  volatile String answer;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/v3.1/send",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          var bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  MailjetMailer mailer(String apiKey) {
    var url = URI.create("http://localhost:" + server.getAddress().getPort());
    return new MailjetMailer(
        new MailProperties(
            "workspaced.online",
            "noreply",
            "workspaced",
            apiKey,
            "private",
            url,
            Duration.ofSeconds(5)),
        JSON);
  }

  @Test
  void sendsTheMessageAndReturnsMailjetsId() throws Exception {
    status = 200;
    answer =
        "{\"Messages\":[{\"Status\":\"success\",\"To\":[{\"Email\":\"ada@example.com\","
            + "\"MessageID\":1152921529051316483}]}]}";
    assertThat(mailer("public").send(MAIL)).isEqualTo("1152921529051316483");
    var message = JSON.readTree(bodies.getFirst()).get("Messages").get(0);
    assertThat(message.get("From").get("Email").asString()).isEqualTo("support@workspaced.online");
    assertThat(message.get("From").get("Name").asString()).isEqualTo("workspaced support");
    assertThat(message.get("To").get(0).get("Email").asString()).isEqualTo("ada@example.com");
    assertThat(message.get("To").get(0).has("Name")).isFalse();
    assertThat(message.get("ReplyTo").get("Email").asString()).isEqualTo("sales@workspaced.online");
    assertThat(message.get("Subject").asString()).isEqualTo("Hello");
    assertThat(message.get("TextPart").asString()).isEqualTo("Hi **Ada**");
    assertThat(message.get("HTMLPart").asString()).isEqualTo("<p>Hi <strong>Ada</strong></p>");
    assertThat(authorizations.getFirst()).isEqualTo("Basic cHVibGljOnByaXZhdGU=");
  }

  @Test
  void sendsPlainTextAloneWhenThereIsNoHtml() throws Exception {
    status = 200;
    answer = "{\"Messages\":[{\"Status\":\"success\",\"To\":[{\"MessageID\":1}]}]}";
    mailer("public")
        .send(new Mail(MAIL.from(), MAIL.to(), null, MAIL.subject(), MAIL.text(), null));
    var message = JSON.readTree(bodies.getFirst()).get("Messages").get(0);
    assertThat(message.has("HTMLPart")).isFalse();
    assertThat(message.has("ReplyTo")).isFalse();
  }

  @Test
  void givesMailjetsReasonForARefusedMessage() {
    status = 400;
    answer =
        "{\"Messages\":[{\"Status\":\"error\",\"Errors\":[{\"ErrorMessage\":"
            + "\"The sender is not verified\"}]}]}";
    assertThatThrownBy(() -> mailer("public").send(MAIL))
        .isInstanceOf(MailFailedException.class)
        .hasMessageContaining("The sender is not verified");
  }

  @Test
  void givesMailjetsReasonForARefusedKey() {
    status = 401;
    answer = "{\"ErrorMessage\":\"API key authentication/authorization failure\"}";
    assertThatThrownBy(() -> mailer("public").send(MAIL))
        .isInstanceOf(MailFailedException.class)
        .hasMessageContaining("authorization failure");
  }

  @Test
  void isUnavailableWhileMailjetIsDown() {
    status = 503;
    answer = "";
    assertThatThrownBy(() -> mailer("public").send(MAIL))
        .isInstanceOf(MailUnavailableException.class);
  }

  @Test
  void sendsNothingWithoutAnAccount() {
    assertThatThrownBy(() -> mailer("").send(MAIL)).isInstanceOf(MailUnavailableException.class);
    assertThat(bodies).isEmpty();
  }
}
