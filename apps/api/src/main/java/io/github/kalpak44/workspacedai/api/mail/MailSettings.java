package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Where mail is sent from")
public record MailSettings(
    @Schema(description = "The domain every sender's address is on", example = "workspaced.online")
        String domain,
    @Schema(
            description = "The address the platform's own notifications come from",
            example = "noreply@workspaced.online")
        String noReply,
    @Schema(
            description =
                "Whether a Mailjet account is configured; without one, sending answers 503")
        boolean configured,
    @Schema(description = "The addresses mail can be sent from, in the order to offer them")
        List<MailSender> senders) {}
