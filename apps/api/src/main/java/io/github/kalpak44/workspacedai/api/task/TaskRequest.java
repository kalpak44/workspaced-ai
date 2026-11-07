package io.github.kalpak44.workspacedai.api.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bounded as instructions are, so one prompt cannot fill the agent's context. */
@Schema(description = "What to replace a task file's schedule and prompt with")
public record TaskRequest(
    @Schema(example = "0 9 * * 1-5", maxLength = 100) @NotNull @Size(max = 100) String cron,
    @Schema(example = "Europe/Sofia", maxLength = 64) @NotNull @Size(max = 64) String timeZone,
    @Schema(example = "Summarise the open PRs.", maxLength = 100_000) @NotNull @Size(max = 100_000)
        String prompt) {}
