package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "The model a workspace's agent replies with")
public record ModelRequest(
    @Schema(
            description = "An id from /models; null returns to the harness's default",
            example = "anthropic/claude-sonnet-5.5",
            maxLength = 200)
        @Size(min = 1, max = 200)
        String model) {}
