package io.github.kalpak44.workspacedai.api.mail;

import io.github.kalpak44.workspacedai.api.mail.Mailer.Address;
import io.github.kalpak44.workspacedai.api.mail.Mailer.Mail;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sends mail as one of the listed senders, inside the site's header and footer and that sender's
 * own template; the platform's own mail goes through here as well as an admin's.
 */
@Service
public class MailService {

  private final Mailer mailer;
  private final MailProperties properties;
  private final MailSenderRepository senders;
  private final MailMarkdown markdown;
  private final MailLayout layout;

  public MailService(
      Mailer mailer,
      MailProperties properties,
      MailSenderRepository senders,
      MailMarkdown markdown,
      MailLayout layout) {
    this.mailer = mailer;
    this.properties = properties;
    this.senders = senders;
    this.markdown = markdown;
    this.layout = layout;
  }

  public MailSettings settings() {
    return new MailSettings(
        properties.domain(),
        address(properties.noReply()),
        properties.isConfigured(),
        senders.findAllByOrderByPositionAsc().stream().map(this::view).toList());
  }

  /** Saves the template one sender's messages go out in; empty for a sender not listed. */
  @Transactional
  public Optional<MailSender> updateTemplate(String localPart, MailTemplateRequest template) {
    return senders
        .findById(localPart)
        .map(
            sender -> {
              sender.update(template.signature().strip(), template.footer().strip());
              return view(sender);
            });
  }

  /**
   * Sends one of the platform's own messages, such as a notice about a workspace or a payment, from
   * the no-reply address.
   *
   * @return the id Mailjet gave the message
   */
  public String notify(List<String> to, String subject, String text) {
    return send(properties.noReply(), to, null, subject, text);
  }

  /**
   * @param localPart one of the listed senders, e.g. support
   * @param text the body as Markdown, sent formatted and, for a client that shows no HTML, as it
   *     was written
   * @return the id Mailjet gave the message
   * @throws ResponseStatusException a 400 when no sender has that local part
   */
  public String send(
      String localPart, List<String> to, String replyTo, String subject, String text) {
    var sender = template(localPart, null);
    var body = withSignature(text, sender.signature());
    return mailer.send(
        new Mail(
            new Address(sender.address(), sender.name()),
            to.stream().map(Address::of).toList(),
            replyTo == null || replyTo.isBlank() ? null : Address.of(replyTo),
            subject,
            layout.text(body, sender.footer()),
            layout.html(subject, markdown.html(body), sender.footer()),
            List.of(layout.logo())));
  }

  /**
   * The HTML part as it would be sent, with the logo as a data URL so a browser shows it.
   *
   * @param template shown instead of the sender's saved one; null shows the saved one
   */
  public MailPreview preview(
      String localPart, String subject, String text, MailTemplateRequest template) {
    var sender = template(localPart, template);
    var html =
        layout.html(
            subject, markdown.html(withSignature(text, sender.signature())), sender.footer());
    var logo = layout.logo();
    var inlined =
        "data:"
            + logo.contentType()
            + ";base64,"
            + Base64.getEncoder().encodeToString(logo.content());
    return new MailPreview(html.replace("cid:" + logo.id(), inlined));
  }

  private MailSender template(String localPart, MailTemplateRequest override) {
    var sender =
        senders
            .findById(localPart)
            .map(this::view)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "no sender " + localPart + " is listed"));
    return override == null
        ? sender
        : new MailSender(
            sender.localPart(),
            sender.address(),
            sender.name(),
            override.signature().strip(),
            override.footer().strip());
  }

  private static String withSignature(String text, String signature) {
    return signature.isBlank() ? text : text.stripTrailing() + "\n\n" + signature;
  }

  private MailSender view(MailSenderEntity sender) {
    return new MailSender(
        sender.getLocalPart(),
        address(sender.getLocalPart()),
        sender.getName(),
        sender.getSignature(),
        sender.getFooter());
  }

  private String address(String localPart) {
    return localPart + "@" + properties.domain();
  }
}
