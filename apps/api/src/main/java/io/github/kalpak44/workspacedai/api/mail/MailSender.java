package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An address mail can be sent from, with the template its messages use")
public record MailSender(
    @Schema(
            description = "The address before the @, which a message names as its sender",
            example = "support")
        String localPart,
    @Schema(example = "support@workspaced.online") String address,
    @Schema(
            description = "Shown beside the address in the reader's inbox",
            example = "workspaced support")
        String name,
    @Schema(
            description = "Markdown added under every message",
            example = "Best regards,\nworkspaced support")
        String signature,
    @Schema(
            description = "One line of plain text at the top of the footer",
            example =
                "You received this from support@workspaced.online. Reply to reach our support team.")
        String footer) {}
