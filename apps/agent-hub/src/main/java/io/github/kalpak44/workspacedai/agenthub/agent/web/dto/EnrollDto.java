package io.github.kalpak44.workspacedai.agenthub.agent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What a VM's supervisor sends to enroll.
 *
 * @param token the enrollment token from the VM's user data
 * @param publicKey base64 (standard, padded) of the raw 32-byte Ed25519 public key
 */
public record EnrollDto(
    @NotNull UUID vmId,
    @NotBlank @Size(max = 100) String token,
    @NotBlank @Size(max = 100) String publicKey) {}
