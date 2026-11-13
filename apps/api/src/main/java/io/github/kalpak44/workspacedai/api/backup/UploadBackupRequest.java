package io.github.kalpak44.workspacedai.api.backup;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "An archive about to be uploaded in parts")
public record UploadBackupRequest(
    @Schema(description = "The archive's whole size in bytes", example = "104857600") @Positive
        long sizeBytes) {}
