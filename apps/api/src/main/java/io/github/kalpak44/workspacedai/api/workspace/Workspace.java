package io.github.kalpak44.workspacedai.api.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "A workspace AI agents run in")
public record Workspace(
    @Schema(
            description = "Assigned by the server",
            example = "3f2c6a52-8a8e-4c1e-9a43-5c0d3c7e2b11")
        UUID id,
    @Schema(description = "Display name", example = "Research", maxLength = 100) String name) {}
