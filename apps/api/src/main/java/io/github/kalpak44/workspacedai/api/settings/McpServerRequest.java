package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * The name is what the agent's tools are prefixed with, so it is kept to a short slug. A header
 * value must reference a workspace secret as {@code {env:NAME}}; credentials stay in Secrets.
 */
@Schema(description = "A remote MCP server to add to a workspace")
public record McpServerRequest(
    @Schema(example = "github", pattern = McpServerRequest.NAME)
        @NotBlank
        @Pattern(regexp = McpServerRequest.NAME)
        String name,
    @Schema(example = "https://api.githubcopilot.com/mcp/", maxLength = 2000)
        @NotBlank
        @Size(max = 2000)
        @Pattern(regexp = "^https?://\\S+$")
        String url,
    @Schema(
            description = "Sent with every request; a value may be {env:SECRET_NAME}",
            example = "{\"Authorization\": \"Bearer {env:GITHUB_TOKEN}\"}")
        @NotNull
        @Size(max = 20)
        Map<@Pattern(regexp = "^[A-Za-z0-9-]{1,100}$") String, @NotNull @Size(max = 4000) String>
            headers) {

  static final String NAME = "^[a-z0-9][a-z0-9_-]{0,63}$";
}
