package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One payment into the balance")
public record TopUp(
    UUID id,
    @Schema(example = "2500") long amountCents,
    TopUpStatus status,
    Instant createdAt,
    @Schema(nullable = true) Instant paidAt,
    @Schema(
            description = "The number Stripe gave the invoice; null until it is issued",
            example = "A1B2C3D4-0001",
            nullable = true)
        String invoiceNumber,
    @Schema(description = "Whether GET /billing/top-ups/{id}/invoice has an invoice to send")
        boolean invoiceAvailable) {}
