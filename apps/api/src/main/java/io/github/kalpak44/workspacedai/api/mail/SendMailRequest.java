package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The sender is one of the listed local parts, so no request can send as an address nobody receives
 * replies for. A line break in the subject is refused rather than passed on.
 */
@Schema(description = "A message to send")
public record SendMailRequest(
    @Schema(
            description =
                "The sender's address before the @, one of the senders mail settings list",
            example = "support",
            maxLength = 64)
        @NotBlank
        @Size(max = 64)
        String from,
    @ArraySchema(schema = @Schema(example = "ada@example.com"), minItems = 1, maxItems = 50)
        @NotEmpty
        @Size(max = 50)
        List<@NotBlank @Email @Size(max = 254) String> to,
    @Schema(
            description = "Where replies go instead of the sender",
            example = "sales@workspaced.online")
        @Email
        @Size(max = 254)
        String replyTo,
    @Schema(example = "Your workspace is ready", maxLength = 200)
        @NotBlank
        @Size(max = 200)
        @Pattern(regexp = "[^\\r\\n]*+")
        String subject,
    @Schema(
            description =
                "The body, as Markdown; sent formatted in the sender's template, and as written for a client that shows no HTML",
            maxLength = 100000)
        @NotBlank
        @Size(max = 100000)
        String text) {}
