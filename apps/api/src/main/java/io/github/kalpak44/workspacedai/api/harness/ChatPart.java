package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One piece of a message: text, the agent's reasoning, or a tool it ran.
 *
 * @param tool for a tool part, the tool's name; null otherwise
 * @param status for a tool part, pending, running, completed or error; null otherwise
 */
@Schema(description = "One piece of a chat message")
public record ChatPart(
    @Schema(example = "prt_1a1f31492001Vcb1bBKz7fR863") String id,
    @Schema(allowableValues = {"text", "reasoning", "tool"}) String type,
    @Schema(description = "The text so far, or what the tool is doing", example = "Hello!")
        String text,
    @Schema(example = "bash") String tool,
    @Schema(allowableValues = {"pending", "running", "completed", "error"}) String status) {}
