package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param inputCost USD per million input tokens, or null when the catalogue does not say
 * @param outputCost USD per million output tokens, or null when the catalogue does not say
 */
@Schema(description = "A model the workspace's agent can reply with")
public record ModelOption(
    @Schema(example = "anthropic/claude-sonnet-5.5") String id,
    @Schema(example = "Claude Sonnet 5.5") String name,
    @Schema(description = "Context window, in tokens", example = "1000000") Long contextLength,
    @Schema(example = "2") Double inputCost,
    @Schema(example = "10") Double outputCost) {}
