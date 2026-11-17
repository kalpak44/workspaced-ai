package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;

@Schema(description = "The Stripe page that saves a card without charging it")
public record CardCheckout(
    @Schema(description = "Send the browser here; it comes back to the billing page")
        URI checkoutUrl) {}
