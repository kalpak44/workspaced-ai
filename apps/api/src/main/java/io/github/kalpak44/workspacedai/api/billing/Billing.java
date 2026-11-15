package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "What the account has to spend, and how it was paid in")
public record Billing(
    @Schema(description = "Rounded down to whole cents", example = "1240") long balanceCents,
    @Schema(example = "eur") String currency,
    @Schema(description = "Newest first; abandoned checkouts are left out") List<TopUp> topUps) {}
