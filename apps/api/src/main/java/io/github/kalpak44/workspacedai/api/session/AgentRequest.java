package io.github.kalpak44.workspacedai.api.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRequest(@NotBlank @Size(max = 200) String agent) {}
