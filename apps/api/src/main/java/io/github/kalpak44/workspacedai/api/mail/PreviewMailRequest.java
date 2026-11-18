package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "A message to show in its sender's template")
public record PreviewMailRequest(
    @Schema(description = "The sender's address before the @", example = "support") @NotBlank
        String from,
    @Schema(example = "Your workspace is ready", maxLength = 200) @NotNull @Size(max = 200)
        String subject,
    @Schema(description = "The body, as Markdown", maxLength = 100000) @NotNull @Size(max = 100000)
        String text,
    @Schema(
            description =
                "A template to show instead of the sender's saved one, e.g. while it is being edited")
        @Valid
        MailTemplateRequest template) {}
