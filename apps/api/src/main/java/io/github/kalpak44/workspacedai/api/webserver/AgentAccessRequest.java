package io.github.kalpak44.workspacedai.api.webserver;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Whether the workspace's agent may change its web server")
public record AgentAccessRequest(@NotNull Boolean allowed) {}
