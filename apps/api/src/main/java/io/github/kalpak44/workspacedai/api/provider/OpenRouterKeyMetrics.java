package io.github.kalpak44.workspacedai.api.provider;

import java.math.BigDecimal;

/**
 * Selected, non-secret fields returned by OpenRouter's current-key endpoint. Null means
 * unavailable.
 */
public record OpenRouterKeyMetrics(
    BigDecimal usage,
    BigDecimal usageDaily,
    BigDecimal usageWeekly,
    BigDecimal usageMonthly,
    BigDecimal limit,
    BigDecimal limitRemaining,
    String limitReset,
    String expiresAt,
    Boolean includeByokInLimit,
    BigDecimal byokUsage,
    Boolean freeTier,
    Long freeRequestsLimit,
    Long freeRequestsRemaining) {}
