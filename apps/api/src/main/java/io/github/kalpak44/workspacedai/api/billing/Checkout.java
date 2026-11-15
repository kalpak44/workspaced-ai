package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.UUID;

@Schema(description = "A started top-up, and the Stripe page that takes its payment")
public record Checkout(
    @Schema(description = "The top-up's id") UUID id,
    @Schema(description = "Send the browser here; it comes back to the billing page")
        URI checkoutUrl) {}
