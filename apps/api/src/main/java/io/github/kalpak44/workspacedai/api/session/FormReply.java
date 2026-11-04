package io.github.kalpak44.workspacedai.api.session;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Values keyed by the field keys in the agent's form. OpenCode validates their types. */
public record FormReply(@NotNull @Size(max = 100) Map<String, Object> answer) {}
