package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "Whether the saved card is charged when the balance runs low, and how much")
public record AutoTopUp(
    @Schema(description = "Only with a saved card") boolean enabled,
    @Schema(
            description = "Charge the card once the balance is below this, in cents",
            example = "1000",
            minimum = "500",
            maximum = "100000")
        @Min(BillingService.MIN_TOP_UP_CENTS)
        @Max(BillingService.MAX_TOP_UP_CENTS)
        long belowCents,
    @Schema(
            description = "What to add each time, in cents; VAT is charged on top",
            example = "2000",
            minimum = "500",
            maximum = "100000")
        @Min(BillingService.MIN_TOP_UP_CENTS)
        @Max(BillingService.MAX_TOP_UP_CENTS)
        long amountCents) {}
