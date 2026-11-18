package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserEntity;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Address;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class MailTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MESSAGE =
      "{\"from\":\"support\",\"fromName\":\" workspaced support \",\"to\":[\"ada@example.com\"],"
          + "\"replyTo\":\"sales@workspaced.online\",\"subject\":\"Hello\",\"text\":\"Hi Ada\"}";

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired MailService service;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(mailer.send(any())).thenReturn("1152921529051316483");
  }

  @Test
  void sendsAsAnAddressOnTheDomain() throws Exception {
    var sent = send("POST", "/mail/messages", MESSAGE);
    assertThat(sent.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(sent.body()).get("id").asString()).isEqualTo("1152921529051316483");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    assertThat(mail.getValue())
        .isEqualTo(
            new Mail(
                new Address("support@workspaced.online", "workspaced support"),
                List.of(Address.of("ada@example.com")),
                Address.of("sales@workspaced.online"),
                "Hello",
                "Hi Ada"));
  }

  @Test
  void saysWhichDomainMailComesFrom() throws Exception {
    var settings = send("GET", "/mail", null);
    assertThat(settings.statusCode()).isEqualTo(200);
    var body = JSON.readTree(settings.body());
    assertThat(body.get("domain").asString()).isEqualTo("workspaced.online");
    assertThat(body.get("noReply").asString()).isEqualTo("noreply@workspaced.online");
    assertThat(body.get("configured").asBoolean()).isFalse();
  }

  @Test
  void sendsThePlatformsOwnMailFromTheNoReplyAddress() {
    assertThat(service.notify(List.of("ada@example.com"), "Workspace ready", "It is up."))
        .isEqualTo("1152921529051316483");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    assertThat(mail.getValue())
        .isEqualTo(
            new Mail(
                new Address("noreply@workspaced.online", "workspaced"),
                List.of(Address.of("ada@example.com")),
                null,
                "Workspace ready",
                "It is up."));
  }

  @Test
  void refusesASenderOnAnotherDomainOrABadMessage() throws Exception {
    for (var bad :
        List.of(
            MESSAGE.replace("\"support\"", "\"support@example.com\""),
            MESSAGE.replace("\"support\"", "\".support\""),
            MESSAGE.replace("[\"ada@example.com\"]", "[]"),
            MESSAGE.replace("ada@example.com", "not an address"),
            MESSAGE.replace("\"Hello\"", "\"Hello\\r\\nBcc: eve@example.com\""),
            MESSAGE.replace("\"Hi Ada\"", "\" \""))) {
      assertThat(send("POST", "/mail/messages", bad).statusCode()).as(bad).isEqualTo(400);
    }
    verify(mailer, never()).send(any());
  }

  @Test
  void passesOnMailjetsRefusal() throws Exception {
    when(mailer.send(any())).thenThrow(new MailFailedException("The sender is not verified"));
    var refused = send("POST", "/mail/messages", MESSAGE);
    assertThat(refused.statusCode()).isEqualTo(502);
    assertThat(JSON.readTree(refused.body()).get("detail").asString())
        .contains("The sender is not verified");
  }

  @Test
  void refusesEveryoneButAnAdmin() throws Exception {
    users.save(new UserEntity("ada", passwords.encode("password1")));
    var ada = "Bearer " + TestUsers.login(port, "ada", "password1");
    assertThat(send("GET", "/mail", null, ada).statusCode()).isEqualTo(403);
    assertThat(send("POST", "/mail/messages", MESSAGE, ada).statusCode()).isEqualTo(403);
    verify(mailer, never()).send(any());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    return send(method, path, body, TestUsers.bearer(port));
  }

  private HttpResponse<String> send(String method, String path, String body, String bearer)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .header("Authorization", bearer);
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    var request =
        builder
            .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
            .build();
    return http.send(request, BodyHandlers.ofString());
  }
}
