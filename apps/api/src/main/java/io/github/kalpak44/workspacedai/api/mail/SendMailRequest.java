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
 * The sender is a local part only, so no request can send as a domain Mailjet has not verified for
 * us. A line break in a header field is refused rather than passed on.
 */
@Schema(description = "A message to send")
public record SendMailRequest(
    @Schema(description = "The sender's address before the @", example = "support", maxLength = 64)
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_%+-]++(?:\\.[A-Za-z0-9_%+-]++)*+")
        String from,
    @Schema(
            description = "Shown beside the sender's address",
            example = "workspaced support",
            maxLength = 100)
        @Size(max = 100)
        @Pattern(regexp = "[^\\r\\n]*")
        String fromName,
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
        @Pattern(regexp = "[^\\r\\n]*")
        String subject,
    @Schema(description = "The body, as plain text", maxLength = 100000)
        @NotBlank
        @Size(max = 100000)
        String text) {

  String trimmedFromName() {
    return fromName == null ? null : fromName.strip();
  }
}
