package io.github.kalpak44.workspacedai.api.harness;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A message in a chat session")
public record ChatMessage(
    @Schema(example = "msg_1a1f30ff5001LcL0VbNMntCSUe") String id,
    @Schema(allowableValues = {"user", "assistant"}) String role,
    List<ChatPart> parts) {}
