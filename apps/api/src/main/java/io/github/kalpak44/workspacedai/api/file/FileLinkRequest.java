package io.github.kalpak44.workspacedai.api.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FileLinkRequest(
    @Schema(
            description = "Relative to the session's folder, as the agent links it",
            example = "outbox/report.pdf")
        @NotBlank
        @Size(max = 500)
        String path) {}
