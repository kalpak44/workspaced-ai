package io.github.kalpak44.workspacedai.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailMarkdownTest {

  private final MailMarkdown markdown = new MailMarkdown();

  @Test
  void formatsWhatTheEditorWrites() {
    var html =
        markdown.html(
            "## Welcome\n\nHi **Ada**,\nyour *workspace* is ~~down~~ up.\n\n- one\n- two\n\n"
                + "| Plan | Price |\n| --- | --- |\n| Small | 5 |\n\n[Open it](https://ai.workspaced.online)");
    assertThat(html)
        .startsWith("<!doctype html>")
        .contains("<h2>Welcome</h2>")
        .contains("Hi <strong>Ada</strong>,<br>\nyour <em>workspace</em> is <del>down</del> up.")
        .contains("<li>one</li>")
        .contains("<td>Small</td>")
        .contains("href=\"https://ai.workspaced.online\">Open it</a>")
        .endsWith("</body></html>");
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
