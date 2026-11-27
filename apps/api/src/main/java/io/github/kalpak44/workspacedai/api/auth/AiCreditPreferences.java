package io.github.kalpak44.workspacedai.api.auth;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Warning threshold in USD; zero disables personal-connector low warnings, never trial warnings or
 * the exhausted-credit block.
 */
public record AiCreditPreferences(
    @NotNull @DecimalMin("0.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2)
        BigDecimal warningThresholdUsd) {}
