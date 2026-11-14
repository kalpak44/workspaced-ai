package io.github.kalpak44.workspacedai.agenthub.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * A token just issued, the one time it exists in plain text.
 *
 * @param token base64url without padding of 32 random bytes
 */
public record IssuedToken(UUID vmId, String token, Instant expiresAt) {}
