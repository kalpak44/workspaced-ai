package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "What the account has to spend, what it spends, and how it was paid in")
public record Billing(
    @Schema(description = "Rounded down to whole cents", example = "1240") long balanceCents,
    @Schema(example = "eur") String currency,
    @Schema(
            description =
                "VAT charged on top of a top-up, in percent; the balance gets the amount"
                    + " before it",
            example = "20")
        int vatPercent,
    @Schema(
            description =
                "Taken from the balance to start a workspace paused for want of funds again, in"
                    + " cents",
            example = "500")
        long reactivationFeeCents,
    @Schema(
            description =
                "How long a workspace stays paused for want of funds before it is deleted, in"
                    + " hours",
            example = "72")
        long pausedForHours,
    @Schema(
            description =
                "What the running workspaces cost together over a month, in cents; paused ones"
                    + " cost nothing",
            example = "1780")
        long monthlySpendCents,
    @Schema(
            description =
                "To the hour, when the balance stops paying for the running workspaces and they"
                    + " are paused; null while none is charged")
        Instant fundedUntil,
    @Schema(description = "Every charged workspace, paused or not, oldest first")
        List<WorkspaceSpend> workspaces,
    @Schema(description = "The last 30 days anything was charged, newest first")
        List<DailySpend> usage,
    @Schema(description = "Newest first; abandoned checkouts are left out") List<TopUp> topUps) {}
