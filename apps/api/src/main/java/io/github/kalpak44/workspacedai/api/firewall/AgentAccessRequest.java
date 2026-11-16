package io.github.kalpak44.workspacedai.api.firewall;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Whether the workspace's agent may open and close ports itself")
public record AgentAccessRequest(@NotNull Boolean allowed) {}
