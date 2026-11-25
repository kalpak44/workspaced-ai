package io.github.kalpak44.workspacedai.api.auth;

import java.util.UUID;

/**
 * Published when an account is first made.
 *
 * @param referral the referral code its sign-up link carried; null when none
 */
public record UserCreated(UUID userId, Role role, String referral) {}
