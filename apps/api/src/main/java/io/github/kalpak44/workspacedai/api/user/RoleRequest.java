package io.github.kalpak44.workspacedai.api.user;

import io.github.kalpak44.workspacedai.api.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "The role a user is given")
public record RoleRequest(@Schema(example = "ADMIN") @NotNull Role role) {}
