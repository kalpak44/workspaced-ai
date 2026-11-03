package io.github.kalpak44.workspacedai.api.session;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A message to the session's agent")
public record MessageRequest(
    @Schema(example = "Add a README describing the project", maxLength = 100000)
        @NotBlank
        @Size(max = 100000)
        String text) {}
