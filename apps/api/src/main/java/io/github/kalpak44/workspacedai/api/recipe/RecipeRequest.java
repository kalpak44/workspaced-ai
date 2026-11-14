package io.github.kalpak44.workspacedai.api.recipe;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded like an instruction file, which is what applying it hands the agent. */
@Schema(description = "A recipe's fields, as created or replaced whole")
public record RecipeRequest(
    @Schema(example = "WordPress with a public address", maxLength = 100) @NotBlank String title,
    @Schema(example = "A WordPress site with MariaDB in Docker", maxLength = 300) String summary,
    @Schema(
            example = "websites",
            allowableValues = {"apps", "websites", "automation", "data", "ai", "devops", "other"})
        @NotNull
        String category,
    @Schema(description = "Up to 8 tags", example = "[\"wordpress\", \"docker\"]")
        List<String> tags,
    @NotNull Visibility visibility,
    @Schema(example = "1. Ask the user to add `DB_PASSWORD` as a secret.", maxLength = 100_000)
        @NotNull
        @Size(min = 1, max = 100_000)
        String content) {

  public static final Set<String> CATEGORIES =
      Set.of("apps", "websites", "automation", "data", "ai", "devops", "other");

  private static final Pattern TAG = Pattern.compile("[a-z0-9-]{1,30}");
  private static final int MAX_TAGS = 8;

  /** The fields as stored: trimmed, tags lowercased and deduplicated in order. */
  record Fields(
      String title,
      String summary,
      String category,
      List<String> tags,
      Visibility visibility,
      String content) {}

  Fields normalized() {
    var trimmedTitle = title.trim();
    if (trimmedTitle.length() > 100) {
      throw invalid("a title is at most 100 characters");
    }
    var trimmedSummary = summary == null ? "" : summary.trim();
    if (trimmedSummary.length() > 300) {
      throw invalid("a summary is at most 300 characters");
    }
    if (!CATEGORIES.contains(category)) {
      throw invalid(
          "the category is one of " + String.join(", ", CATEGORIES.stream().sorted().toList()));
    }
    var unique = new LinkedHashSet<String>();
    for (var tag : tags == null ? List.<String>of() : tags) {
      var normal = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
      if (!TAG.matcher(normal).matches()) {
        throw invalid("a tag is 1 to 30 lowercase letters, digits and -");
      }
      unique.add(normal);
    }
    if (unique.size() > MAX_TAGS) {
      throw invalid("a recipe has at most " + MAX_TAGS + " tags");
    }
    return new Fields(
        trimmedTitle, trimmedSummary, category, new ArrayList<>(unique), visibility, content);
  }

  private static ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
