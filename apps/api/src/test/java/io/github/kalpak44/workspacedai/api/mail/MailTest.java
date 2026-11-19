package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.workspacedai.api.TestDatabase;
import io.github.kalpak44.workspacedai.api.TestRuntime;
import io.github.kalpak44.workspacedai.api.TestUsers;
import io.github.kalpak44.workspacedai.api.auth.UserRepository;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Address;
import io.github.kalpak44.workspacedai.api.mail.Mailer.InlineImage;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestDatabase.class, TestRuntime.class})
class MailTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String MESSAGE =
      "{\"from\":\"support\",\"to\":[\"ada@example.com\"],"
          + "\"replyTo\":\"sales@workspaced.online\",\"subject\":\"Hello\",\"text\":\"Hi **Ada**\"}";

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired MailService service;
  @MockitoBean Mailer mailer;

  private final HttpClient http = HttpClient.newHttpClient();
  private List<MailSender> seeded;

  @BeforeEach
  void reset() {
    users.findAll().stream().filter(u -> !u.getId().equals(TestUsers.ADMIN)).forEach(users::delete);
    when(mailer.send(any())).thenReturn("1152921529051316483");
    seeded = service.settings().senders();
  }

  // Templates are shared rows, so a test that edits one puts it back for the next.
  @AfterEach
  void restoreTemplates() {
    for (var sender : seeded) {
      service.updateTemplate(
          sender.localPart(), new MailTemplateRequest(sender.signature(), sender.footer()));
    }
  }

  @Test
  void sendsInTheSendersTemplate() throws Exception {
    var sent = send("POST", "/mail/messages", MESSAGE);
    assertThat(sent.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(sent.body()).get("id").asString()).isEqualTo("1152921529051316483");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    var sentMail = mail.getValue();
    assertThat(sentMail.from())
        .isEqualTo(new Address("support@workspaced.online", "workspaced support"));
    assertThat(sentMail.to()).containsExactly(Address.of("ada@example.com"));
    assertThat(sentMail.replyTo()).isEqualTo(Address.of("sales@workspaced.online"));
    assertThat(sentMail.subject()).isEqualTo("Hello");
    assertThat(sentMail.text())
        .startsWith("Hi **Ada**\n\nBest regards,\nworkspaced support\n\n-- \n")
        .contains("Reply to reach our support team.");
    assertThat(sentMail.html())
        .contains("<title>Hello</title>")
        .contains(">Hi <strong>Ada</strong></p>")
        .contains("Best regards,<br>\nworkspaced support</p>")
        .contains("You received this from support@workspaced.online. Reply to reach our support")
        .contains("src=\"cid:logo\"");
    assertThat(sentMail.images()).extracting(InlineImage::id).containsExactly("logo");
  }

  @Test
  void listsTheSendersInOrderWithTheirTemplates() throws Exception {
    var settings = send("GET", "/mail", null);
    assertThat(settings.statusCode()).isEqualTo(200);
    var body = JSON.readTree(settings.body());
    assertThat(body.get("domain").asString()).isEqualTo("workspaced.online");
    assertThat(body.get("noReply").asString()).isEqualTo("noreply@workspaced.online");
    assertThat(body.get("configured").asBoolean()).isFalse();
    var senders = body.get("senders");
    assertThat(senders.valueStream().map(sender -> sender.get("localPart").asString()))
        .containsExactly("contact", "support", "sales", "investor", "noreply");
    assertThat(senders.get(1).get("address").asString()).isEqualTo("support@workspaced.online");
    assertThat(senders.get(1).get("signature").asString())
        .isEqualTo("Best regards,\nworkspaced support");
  }

  @Test
  void sendsWithAnEditedTemplate() throws Exception {
    var saved =
        send(
            "PUT",
            "/mail/senders/sales",
            "{\"signature\":\"Talk soon,\\n**Pavel**\","
                + "\"footer\":\"Questions? Just reply.\"}");
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(saved.body()).get("footer").asString())
        .isEqualTo("Questions? Just reply.");

    send("POST", "/mail/messages", MESSAGE.replace("\"support\"", "\"sales\""));
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    assertThat(mail.getValue().from().name()).isEqualTo("workspaced sales");
    assertThat(mail.getValue().html())
        .contains("Talk soon,<br>\n<strong>Pavel</strong>")
        .contains("Questions? Just reply.");
  }

  @Test
  void refusesAnUnknownSenderOrABadTemplate() throws Exception {
    var footerWithBreak = "{\"signature\":\"\",\"footer\":\"a\\nb\"}";
    assertThat(send("PUT", "/mail/senders/sales", footerWithBreak).statusCode()).isEqualTo(400);
    var missingFooter = "{\"signature\":\"\"}";
    assertThat(send("PUT", "/mail/senders/sales", missingFooter).statusCode()).isEqualTo(400);
    var valid = "{\"signature\":\"\",\"footer\":\"\"}";
    assertThat(send("PUT", "/mail/senders/hello", valid).statusCode()).isEqualTo(404);
  }

  @Test
  void previewsAnUnsavedTemplateWithoutSending() throws Exception {
    var preview =
        send(
            "POST",
            "/mail/preview",
            "{\"from\":\"contact\",\"subject\":\"Hi\",\"text\":\"Hello **there**\","
                + "\"template\":{\"signature\":\"Draft sign-off\",\"footer\":\"Draft footer\"}}");
    assertThat(preview.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(preview.body()).get("html").asString())
        .contains(">Hello <strong>there</strong></p>")
        .contains("Draft sign-off")
        .contains("Draft footer")
        .contains("src=\"data:image/png;base64,")
        .doesNotContain("cid:logo");
    assertThat(service.settings().senders().getFirst().signature())
        .isEqualTo("Best regards,\nthe workspaced team");
    verify(mailer, never()).send(any());
  }

  @Test
  void sendsThePlatformsOwnMailFromTheNoReplyAddress() {
    assertThat(service.notify(List.of("ada@example.com"), "Workspace ready", "It is up."))
        .isEqualTo("1152921529051316483");
    var mail = ArgumentCaptor.forClass(Mail.class);
    verify(mailer).send(mail.capture());
    var sentMail = mail.getValue();
    assertThat(sentMail.from()).isEqualTo(new Address("noreply@workspaced.online", "workspaced"));
    assertThat(sentMail.replyTo()).isNull();
    assertThat(sentMail.html()).contains("Replies to this address are not read.");
    assertThat(sentMail.text()).startsWith("It is up.\n\n-- \nThis is an automated message");
  }

  @Test
  void refusesASenderNotListedOrABadMessage() throws Exception {
    for (var bad :
        List.of(
            MESSAGE.replace("\"support\"", "\"hello\""),
            MESSAGE.replace("\"support\"", "\"support@example.com\""),
            MESSAGE.replace("[\"ada@example.com\"]", "[]"),
            MESSAGE.replace("ada@example.com", "not an address"),
            MESSAGE.replace("\"Hello\"", "\"Hello\\r\\nBcc: eve@example.com\""),
            MESSAGE.replace("\"Hi **Ada**\"", "\" \""))) {
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
    users.save(TestUsers.newUser("ada@example.com"));
    var ada = TestUsers.bearer(users.findByEmail("ada@example.com").orElseThrow());
    var template = "{\"signature\":\"\",\"footer\":\"\"}";
    assertThat(send("GET", "/mail", null, ada).statusCode()).isEqualTo(403);
    assertThat(send("POST", "/mail/messages", MESSAGE, ada).statusCode()).isEqualTo(403);
    assertThat(send("PUT", "/mail/senders/sales", template, ada).statusCode()).isEqualTo(403);
    assertThat(send("POST", "/mail/preview", MESSAGE, ada).statusCode()).isEqualTo(403);
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
