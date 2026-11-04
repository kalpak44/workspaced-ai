package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * One entry of an {@code mcpServers} config: a remote server by {@code url}, or a local one the
 * workspace starts by {@code command}. The name is what the agent's tools are prefixed with, so it
 * is kept to a short slug. Header and environment values must reference workspace secrets;
 * credentials stay in Secrets.
 */
@Schema(description = "An MCP server to add to a workspace: either url or command")
public record McpServerRequest(
    @Schema(example = "github", pattern = McpServerRequest.NAME_PATTERN)
        @NotBlank
        @Pattern(regexp = McpServerRequest.NAME_PATTERN)
        String name,
    @Schema(
            description = "A remote server's endpoint",
            example = "https://api.githubcopilot.com/mcp/",
            maxLength = 2000)
        @Size(max = 2000)
        @Pattern(regexp = "^https?://\\S+$")
        String url,
    @Schema(
            description =
                "Sent with every request to a remote server; a value may be {env:SECRET_NAME}",
            example = "{\"Authorization\": \"Bearer {env:GITHUB_TOKEN}\"}")
        @Size(max = 20)
        Map<@Pattern(regexp = "^[A-Za-z0-9-]{1,100}$") String, @NotNull @Size(max = 4000) String>
            headers,
    @Schema(
            description = "The program that starts a local server",
            example = "npx",
            maxLength = 200)
        @Size(min = 1, max = 200)
        @Pattern(regexp = "^\\S+$")
        String command,
    @Schema(description = "The program's arguments", example = "[\"@browserbasehq/mcp\"]")
        @Size(max = 50)
        List<@NotNull @Size(max = 4000) String> args,
    @Schema(
            description =
                "A local server's environment. Each value is {env:SECRET_NAME}, or empty for the "
                    + "secret of the variable's own name",
            example = "{\"BROWSERBASE_API_KEY\": \"\"}")
        @Size(max = 50)
        Map<@Pattern(regexp = ENV_NAME) String, @NotNull @Size(max = 200) String> env) {

  static final String NAME_PATTERN = "^[a-z0-9][a-z0-9_-]{0,63}$";
  static final String ENV_NAME = "^[A-Za-z_][A-Za-z0-9_]{0,99}$";

  /** The remote form, as the API took it before local servers. */
  public McpServerRequest(String name, String url, Map<String, String> headers) {
    this(name, url, headers, null, null, null);
  }
}
