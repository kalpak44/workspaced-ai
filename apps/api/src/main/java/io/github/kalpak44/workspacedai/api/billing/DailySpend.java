package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "What the workspaces were charged on one day")
public record DailySpend(
    @Schema(description = "The day in UTC", example = "2026-11-16") LocalDate date,
    @Schema(description = "Rounded to whole cents", example = "29") long amountCents) {}
