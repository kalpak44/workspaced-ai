package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "The time zone a workspace's agent reads times in")
public record TimeZoneRequest(
    @Schema(description = "An IANA time zone", example = "Europe/Sofia") @NotBlank @Size(max = 64)
        String timeZone) {}
