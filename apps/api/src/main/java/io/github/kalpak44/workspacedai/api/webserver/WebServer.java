package io.github.kalpak44.workspacedai.api.webserver;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A workspace's public web server")
public record WebServer(
    @Schema(
            description =
                "The domain names are taken under; null when this instance offers no web server",
            example = "workspaced.online")
        String domain,
    @Schema(description = "The name it is public under; null while it is off", example = "my-app")
        String name,
    @Schema(
            description = "Where it answers; null while it is off",
            example = "https://box-my-app.workspaced.online/")
        String url,
    @Schema(description = "Whether the workspace's agent may turn it on, off and rename it")
        boolean agentAllowed) {}
