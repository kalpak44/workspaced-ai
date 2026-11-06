package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The credentials to sign in with")
public record LoginRequest(
    @Schema(example = "admin", maxLength = 100) @NotBlank @Size(max = 100) String username,
    @Schema(example = "admin", maxLength = 200) @NotBlank @Size(max = 200) String password) {}
