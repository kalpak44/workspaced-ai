package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailMarkdownTest {

  private final MailMarkdown markdown = new MailMarkdown();

  @Test
  void formatsWhatTheEditorWritesWithInlineStyles() {
    var html =
        markdown.html(
            "## Welcome\n\nHi **Ada**,\nyour *workspace* is ~~down~~ up.\n\n- one\n- two\n\n"
                + "| Plan | Price |\n| --- | --- |\n| Small | 5 |\n\n[Open it](https://ai.workspaced.online)");
    assertThat(html)
        .contains("<h2 style=\"margin:24px 0 12px;")
        .contains(">Welcome</h2>")
        .contains("Hi <strong>Ada</strong>,<br>\nyour <em>workspace</em> is <del>down</del> up.")
        .contains("<li style=\"margin:0 0 4px\">one</li>")
        .contains("<td style=\"border:1px solid #e4e4e7;padding:6px 10px\">Small</td>")
        .contains("href=\"https://ai.workspaced.online\"")
        .contains(">Open it</a>");
  }

  @Test
  void dropsTheOuterMarginsTheCardsPaddingAlreadyGives() {
    assertThat(markdown.html("## Hi\n\nOne\n\nTwo"))
        .contains(
            "<h2 style=\"margin:24px 0 12px;font-size:18px;line-height:1.35;font-weight:600;margin-top:0\">")
        .contains("<p style=\"margin:0 0 16px\">One</p>")
        .contains("<p style=\"margin:0 0 16px;margin-bottom:0\">Two</p>");
  }

  @Test
  void showsRawHtmlAndRefusesScriptLinks() {
    var html = markdown.html("<script>alert(1)</script>\n\n[click](javascript:alert(1))");
    assertThat(html)
        .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        .doesNotContain("<script>")
        .doesNotContain("javascript:");
  }
}
