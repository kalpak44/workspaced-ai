package io.github.kalpak44.workspacedai.api.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "A domain, or a name under one, to point at the workspace")
public record AttachDomainRequest(
    @Schema(example = "app.example.com") @NotBlank @Size(max = 300) String name) {}
