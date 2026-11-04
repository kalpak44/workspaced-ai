package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public server metadata from OpenCode; configuration and credentials are never returned. */
@Schema(description = "An MCP server and whether the workspace's agent is connected to it")
public record McpServer(
    @Schema(example = "github") String name,
    @Schema(
            description =
                "As the harness reports it: connected, failed, needs_auth, disabled; pending "
                    + "while it has not picked the server up yet; unknown while it is not running",
            example = "connected")
        String status) {}
