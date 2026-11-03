package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Header values are never returned: one may hold a token typed in by hand. */
@Schema(description = "A remote MCP server and whether the workspace's agent is connected to it")
public record McpServer(
    @Schema(example = "github") String name,
    @Schema(example = "https://api.githubcopilot.com/mcp/") String url,
    @Schema(description = "The headers it is sent, by name", example = "[\"Authorization\"]")
        List<String> headers,
    @Schema(
            description =
                "As the harness reports it: connected, failed, needs_auth, disabled; pending "
                    + "while it has not picked the server up yet; unknown while it is not running",
            example = "connected")
        String status) {}
