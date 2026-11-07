package io.github.kalpak44.workspacedai.api.variable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bounded so a value fits on one line of the file and in the environment. */
@Schema(description = "One of a workspace's variables, set in its environment when it starts")
public record Variable(
    @Schema(example = "LOG_LEVEL", maxLength = 100)
        @NotNull
        @Size(min = 1, max = 100)
        @Pattern(regexp = Variable.NAME_PATTERN)
        String name,
    @Schema(example = "info", maxLength = 4096) @NotNull @Size(max = 4096) String value) {

  /** An environment variable's name: letters, digits and underscores, not starting with a digit. */
  public static final String NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
}
