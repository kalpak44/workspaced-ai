package io.github.kalpak44.workspacedai.agenthub.agent.web.dto;

import java.time.Instant;
import java.util.UUID;

/** A token for the VM manager to put in the VM's user data; it is never shown again. */
public record IssuedTokenDto(UUID vmId, String token, Instant expiresAt) {}
