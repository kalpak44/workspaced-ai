package io.github.kalpak44.workspacedai.api.webserver;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "The name to make a workspace's web server public under")
public record WebServerRequest(
    @Schema(
            description = "One DNS label: lowercase letters, digits and inner hyphens",
            example = "my-app",
            maxLength = 59)
        @NotNull
        @Pattern(regexp = WebServerService.NAME)
        String name) {}
