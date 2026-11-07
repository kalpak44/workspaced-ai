package io.github.kalpak44.workspacedai.api.task;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.Locale;

/**
 * One of a workspace's scheduled tasks. In the workspace it is a Markdown file whose front matter
 * holds the schedule and whose body is the prompt:
 *
 * <pre>
 * ---
 * cron: 0 9 * * 1-5
 * timezone: Europe/Sofia
 * ---
 * Check the open pull requests and summarise them.
 * </pre>
 */
@Schema(description = "A prompt the workspace's agent runs, in a new session, on a schedule")
public record TaskFile(
    @Schema(description = "The file's name, without .md", example = "pr-summary") String name,
    @Schema(
            description =
                "Five fields (minute, hour, day of month, month, day of week) or a macro such as"
                    + " @daily; empty when the file has none",
            example = "0 9 * * 1-5")
        String cron,
    @Schema(
            description = "The IANA time zone the cron expression is read in",
            example = "Europe/Sofia")
        String timeZone,
    @Schema(description = "Markdown sent to the agent", example = "Summarise the open PRs.")
        String prompt) {

  private static final String FENCE = "---";

  /**
   * Reads a file as written by {@link #toMarkdown} or by the agent. Values may be quoted; a file
   * without front matter is all prompt and has no schedule, so it never runs.
   */
  static TaskFile parse(String name, String text) {
    var lines = text.replace("\r\n", "\n").split("\n", -1);
    if (lines.length == 0 || !lines[0].strip().equals(FENCE)) {
      return new TaskFile(name, "", "", text);
    }
    var cron = "";
    var timeZone = "";
    for (var i = 1; i < lines.length; i++) {
      var line = lines[i].strip();
      if (line.equals(FENCE)) {
        var prompt = String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
        return new TaskFile(name, cron, timeZone, prompt.strip());
      }
      var colon = line.indexOf(':');
      if (colon < 0) continue;
      var key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      var value = unquote(line.substring(colon + 1).strip());
      switch (key) {
        case "cron" -> cron = value;
        case "timezone", "time_zone", "tz" -> timeZone = value;
        default -> {}
      }
    }
    return new TaskFile(name, "", "", text);
  }

  String toMarkdown() {
    return FENCE + "\ncron: " + cron + "\ntimezone: " + timeZone + "\n" + FENCE + "\n" + prompt;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.startsWith("\"") && value.endsWith("\"")
            || value.startsWith("'") && value.endsWith("'"))) {
      return value.substring(1, value.length() - 1).strip();
    }
    return value;
  }
}
