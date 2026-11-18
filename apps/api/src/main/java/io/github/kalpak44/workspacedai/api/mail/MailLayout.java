package io.github.kalpak44.workspacedai.api.mail;

import io.github.kalpak44.workspacedai.api.mail.Mailer.InlineImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Year;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The site's header and footer around every message, so whoever writes one types only what it says.
 * The logo travels inside the mail, since many clients hold back remote images.
 */
@Component
public class MailLayout {

  private final String template;
  private final InlineImage logo;
  private final Clock clock;

  @Autowired
  MailLayout() {
    this(Clock.systemUTC());
  }

  MailLayout(Clock clock) {
    this.clock = clock;
    this.template = read("mail/layout.html");
    this.logo = new InlineImage("logo", "image/png", "logo.png", bytes("mail/logo.png"));
  }

  public InlineImage logo() {
    return logo;
  }

  /**
   * @param body the message, already rendered to HTML
   * @param footer the sender's line at the top of the footer, as plain text
   */
  public String html(String subject, String body, String footer) {
    // The body goes in last, so a placeholder typed into a message is left as it was typed.
    return template
        .replace("{{title}}", HtmlUtils.htmlEscape(subject))
        .replace("{{sender}}", HtmlUtils.htmlEscape(footer))
        .replace("{{year}}", Integer.toString(Year.now(clock).getValue()))
        .replace("{{content}}", body);
  }

  /** The message as it was written, with the footer a client showing no HTML still needs. */
  public String text(String body, String footer) {
    return body.stripTrailing()
        + "\n\n-- \n"
        + (footer.isBlank() ? "" : footer + "\n")
        + "workspaced-ai · https://workspaced.online\n"
        + "Support: https://workspaced.online/support\n";
  }

  private static String read(String path) {
    return new String(bytes(path), StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String path) {
    try (var in = new ClassPathResource(path).getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }
}
