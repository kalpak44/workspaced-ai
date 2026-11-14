package io.github.kalpak44.workspacedai.agenthub.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * A token issued for one VM, kept only as its SHA-256 so the table alone cannot enroll anyone.
 *
 * @param hash SHA-256 of the token as the VM presents it
 */
public record EnrollmentToken(UUID vmId, byte[] hash, Instant expiresAt) {}
