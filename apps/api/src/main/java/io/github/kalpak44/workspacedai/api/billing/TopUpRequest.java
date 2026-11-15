package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(description = "How much to add to the balance")
public record TopUpRequest(
    @Schema(example = "2500", minimum = "500", maximum = "100000")
        @Min(BillingService.MIN_TOP_UP_CENTS)
        @Max(BillingService.MAX_TOP_UP_CENTS)
        long amountCents) {}
