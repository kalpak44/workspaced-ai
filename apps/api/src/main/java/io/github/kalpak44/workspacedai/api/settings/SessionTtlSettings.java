package io.github.kalpak44.workspacedai.api.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** A workspace's session retention policy, independent of its running harness. */
public record SessionTtlSettings(
    @NotNull Boolean sessionTtlEnabled,
    @NotNull @Schema(allowableValues = {"1", "6", "24", "72", "168", "720"})
        Integer sessionTtlHours) {}
