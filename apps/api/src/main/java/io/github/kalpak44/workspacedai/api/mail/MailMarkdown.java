package io.github.kalpak44.workspacedai.api.mail;

import java.util.List;
import java.util.Map;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/** Renders a message's Markdown as the body of its HTML part, styled like the site. */
@Component
public class MailMarkdown {

  private static final List<Extension> EXTENSIONS =
      List.of(TablesExtension.create(), StrikethroughExtension.create());

  // Inline on every element, because mail clients drop stylesheets.
  private static final Map<String, String> STYLES =
      Map.ofEntries(
          Map.entry("p", "margin:0 0 16px"),
          Map.entry("h1", "margin:0 0 16px;font-size:22px;line-height:1.3;font-weight:600"),
          Map.entry("h2", "margin:24px 0 12px;font-size:18px;line-height:1.35;font-weight:600"),
          Map.entry("h3", "margin:20px 0 8px;font-size:16px;font-weight:600"),
          Map.entry("a", "color:#18181b;text-decoration:underline"),
          Map.entry("ul", "margin:0 0 16px;padding-left:20px"),
          Map.entry("ol", "margin:0 0 16px;padding-left:20px"),
          Map.entry("li", "margin:0 0 4px"),
          Map.entry(
              "blockquote",
              "margin:0 0 16px;padding:0 0 0 12px;border-left:3px solid #e4e4e7;color:#52525b"),
          Map.entry(
              "code",
              "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;"
                  + "background:#f4f4f5;border-radius:4px;padding:1px 4px"),
          Map.entry(
              "pre",
              "margin:0 0 16px;padding:12px;background:#f4f4f5;border-radius:8px;"
                  + "overflow-x:auto;white-space:pre-wrap"),
          Map.entry("hr", "margin:24px 0;border:0;border-top:1px solid #e4e4e7"),
          Map.entry("table", "margin:0 0 16px;border-collapse:collapse"),
          Map.entry(
              "th", "border:1px solid #e4e4e7;padding:6px 10px;background:#fafafa;text-align:left"),
          Map.entry("td", "border:1px solid #e4e4e7;padding:6px 10px"));

  // The card around the body has its own padding, so the outermost blocks drop the margin
  // that would otherwise double it.
  private static final AttributeProvider STYLED =
      (node, tag, attributes) -> {
        var style = STYLES.get(tag);
        if (style == null) {
          return;
        }
        if (node.getParent() instanceof Document) {
          if (node.getPrevious() == null) {
            style += ";margin-top:0";
          }
          if (node.getNext() == null) {
            style += ";margin-bottom:0";
          }
        }
        attributes.put("style", style);
      };

  private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();

  // Raw HTML in the Markdown is escaped and a javascript: link dropped, so what is typed is
  // shown, never run; a single line break stays one, as it was typed.
  private final HtmlRenderer renderer =
      HtmlRenderer.builder()
          .extensions(EXTENSIONS)
          .attributeProviderFactory(context -> STYLED)
          .escapeHtml(true)
          .sanitizeUrls(true)
          .softbreak("<br>\n")
          .build();

  public String html(String markdown) {
    return renderer.render(parser.parse(markdown));
  }
}
