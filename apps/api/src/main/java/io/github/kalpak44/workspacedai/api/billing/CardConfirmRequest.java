package io.github.kalpak44.workspacedai.api.billing;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "The setup session the browser came back from")
public record CardConfirmRequest(
    @Schema(description = "The card_session Stripe put in the return URL", example = "cs_test_a1")
        @NotBlank
        String sessionId) {}
