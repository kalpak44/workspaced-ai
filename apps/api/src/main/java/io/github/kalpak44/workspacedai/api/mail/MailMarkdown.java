package io.github.kalpak44.workspacedai.api.mail;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/** Renders a message's Markdown as the HTML part of its mail. */
@Component
public class MailMarkdown {

  private static final List<Extension> EXTENSIONS =
      List.of(TablesExtension.create(), StrikethroughExtension.create());

  // Mail clients drop most stylesheets, so what matters is inline on the body; the rest only
  // keeps tables readable where a client does honour it.
  private static final String HEAD =
      "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
          + "table{border-collapse:collapse}th,td{border:1px solid #d4d4d8;padding:4px 8px}"
          + "</style></head><body style=\"font-family:-apple-system,'Segoe UI',Helvetica,Arial,"
          + "sans-serif;font-size:15px;line-height:1.5;color:#18181b\">";
  private static final String TAIL = "</body></html>";

  private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();

  // Raw HTML in the Markdown is escaped and a javascript: link dropped, so what is typed is
  // shown, never run; a single line break stays one, as it was typed.
  private final HtmlRenderer renderer =
      HtmlRenderer.builder()
          .extensions(EXTENSIONS)
          .escapeHtml(true)
          .sanitizeUrls(true)
          .softbreak("<br>\n")
          .build();

  public String html(String markdown) {
    return HEAD + renderer.render(parser.parse(markdown)) + TAIL;
  }
}
