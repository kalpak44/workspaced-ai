package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MailLayoutTest {

  private final MailLayout layout =
      new MailLayout(Clock.fixed(Instant.parse("2027-03-01T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void wrapsTheBodyInTheSitesHeaderAndFooter() {
    var html = layout.html("Hello & welcome", "<p>Hi</p>", "Reply to <us>.");
    assertThat(html)
        .startsWith("<!doctype html>")
        .contains("<title>Hello &amp; welcome</title>")
        .contains("<img src=\"cid:logo\"")
        .contains("workspaced<span style=\"color:#a1a1aa\">-ai</span>")
        .contains("<p>Hi</p>")
        .contains("Reply to &lt;us&gt;.")
        .contains("https://workspaced.online/privacy")
        .contains("&copy; 2027 Workspaced AI.");
  }

  @Test
  void leavesAPlaceholderTypedIntoAMessageAsItWasTyped() {
    var html = layout.html("Hi", "<p>{{year}} {{sender}}</p>", "");
    assertThat(html).contains("<p>{{year}} {{sender}}</p>");
  }

  @Test
  void endsThePlainTextWithTheFooter() {
    assertThat(layout.text("Hi\n\n", "Replies are not read."))
        .isEqualTo(
            "Hi\n\n-- \nReplies are not read.\nworkspaced-ai · https://workspaced.online\n"
                + "Support: https://workspaced.online/support\n");
    assertThat(layout.text("Hi", " ")).startsWith("Hi\n\n-- \nworkspaced-ai");
  }

  @Test
  void carriesTheLogoInsideTheMail() {
    var logo = layout.logo();
    assertThat(logo.id()).isEqualTo("logo");
    assertThat(logo.contentType()).isEqualTo("image/png");
    assertThat(logo.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
  }
}
