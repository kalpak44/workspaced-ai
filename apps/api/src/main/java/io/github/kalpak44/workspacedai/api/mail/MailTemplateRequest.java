package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A line break in the footer is refused; it is a single line in the mail. */
@Schema(description = "The template one sender's messages go out in")
public record MailTemplateRequest(
    @Schema(
            description = "Markdown added under every message; empty adds nothing",
            maxLength = 5000)
        @NotNull
        @Size(max = 5000)
        String signature,
    @Schema(description = "One line of plain text at the top of the footer", maxLength = 300)
        @NotNull
        @Size(max = 300)
        @Pattern(regexp = "[^\\r\\n]*+")
        String footer) {}
