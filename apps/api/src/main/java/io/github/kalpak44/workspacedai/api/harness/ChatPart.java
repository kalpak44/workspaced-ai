package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * One piece of a message: text, the agent's reasoning, or a tool it ran.
 *
 * @param tool for a tool part, the tool's name; null otherwise
 * @param status for a tool part, pending, running, completed or error; null otherwise
 * @param input for a tool part, what it was called with, once known; null otherwise
 * @param output for a finished tool part, its text output or error, cut to {@link #MAX_OUTPUT}
 *     characters; null otherwise
 */
@Schema(description = "One piece of a chat message")
public record ChatPart(
    @Schema(example = "prt_1a1f31492001Vcb1bBKz7fR863") String id,
    @Schema(allowableValues = {"text", "reasoning", "tool"}) String type,
    @Schema(description = "The text so far, or what the tool is doing", example = "Hello!")
        String text,
    @Schema(example = "bash") String tool,
    @Schema(allowableValues = {"pending", "running", "completed", "error"}) String status,
    @Schema(
            description = "The tool's arguments, as the agent gave them",
            example = "{\"command\":\"ls -la\"}")
        JsonNode input,
    @Schema(description = "The tool's output, or why it failed", example = "total 0")
        String output) {

  /** Enough to read what a command printed, without one huge output filling the page. */
  public static final int MAX_OUTPUT = 50_000;

  public ChatPart(String id, String type, String text, String tool, String status) {
    this(id, type, text, tool, status, null, null);
  }

  /** The same tool part in a new state; input and output are kept unless replaced. */
  ChatPart with(String status, JsonNode input, String output) {
    return new ChatPart(
        id,
        type,
        text,
        tool,
        status,
        input == null ? this.input : input,
        output == null ? this.output : output);
  }
}
