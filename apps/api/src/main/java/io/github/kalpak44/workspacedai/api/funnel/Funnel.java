package io.github.kalpak44.workspacedai.api.funnel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "What became of the accounts made between two dates, admins left out")
public record Funnel(
    @Schema(description = "First day of sign-ups counted, UTC", example = "2026-11-01")
        LocalDate from,
    @Schema(description = "Last day of sign-ups counted, UTC", example = "2026-11-30") LocalDate to,
    Counts total,
    @Schema(description = "The same counts per source and campaign, the largest first")
        List<Source> sources,
    @Schema(description = "How far those accounts got in the guided tour, per step")
        List<TourStep> tour) {

  @Schema(description = "Accounts at each step from sign-up to paying")
  public record Counts(
      @Schema(example = "120") long signedUp,
      @Schema(description = "Started their free demo", example = "80") long demoStarted,
      @Schema(description = "Paid at least one top-up", example = "12") long paid,
      @Schema(
              description = "Made 30 days ago or more, so they can count as still paying",
              example = "90")
          long retentionEligible,
      @Schema(
              description = "Charged for a workspace 30 days or more after signing up",
              example = "6")
          long retained) {}

  @Schema(description = "Accounts whose sign-up link named this source and campaign")
  public record Source(
      @Schema(description = "utm_source; null for no source", example = "news.ycombinator.com")
          String source,
      @Schema(description = "utm_campaign; null for none", example = "show-hn") String campaign,
      Counts counts) {}

  @Schema(description = "One step of the guided tour, as the web app numbers them")
  public record TourStep(
      @Schema(example = "5") int step,
      @Schema(description = "Accounts that reached it", example = "60") long reached,
      @Schema(description = "Accounts whose tour is still open at it", example = "4") long open,
      @Schema(
              description = "Accounts that skipped the tour here and have not resumed",
              example = "9")
          long paused,
      @Schema(
              description = "Accounts whose tour ended here; at the last step that is finishing",
              example = "3")
          long ended) {}
}
