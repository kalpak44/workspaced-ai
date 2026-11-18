package io.github.kalpak44.workspacedai.api.mail;

import io.github.kalpak44.workspacedai.api.mail.Mailer.Address;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Sends mail as an address on the configured domain, the only one Mailjet will send for; the
 * platform's own mail goes through here as well as an admin's.
 */
@Service
public class MailService {

  private final Mailer mailer;
  private final MailProperties properties;
  private final MailMarkdown markdown;

  public MailService(Mailer mailer, MailProperties properties, MailMarkdown markdown) {
    this.mailer = mailer;
    this.properties = properties;
    this.markdown = markdown;
  }

  public MailSettings settings() {
    return new MailSettings(
        properties.domain(),
        properties.noReply() + "@" + properties.domain(),
        properties.isConfigured());
  }

  /**
   * Sends one of the platform's own messages, such as a notice about a workspace or a payment, from
   * the no-reply address.
   *
   * @return the id Mailjet gave the message
   */
  public String notify(List<String> to, String subject, String text) {
    return send(properties.noReply(), properties.noReplyName(), to, null, subject, text);
  }

  /**
   * @param localPart the sender's address before the @, e.g. support
   * @param text the body as Markdown, sent formatted and, for a client that shows no HTML, as it
   *     was written
   * @return the id Mailjet gave the message
   */
  public String send(
      String localPart,
      String fromName,
      List<String> to,
      String replyTo,
      String subject,
      String text) {
    var from = new Address(localPart + "@" + properties.domain(), fromName);
    return mailer.send(
        new Mail(
            from,
            to.stream().map(Address::of).toList(),
            replyTo == null || replyTo.isBlank() ? null : Address.of(replyTo),
            subject,
            text,
            markdown.html(text)));
  }
}
