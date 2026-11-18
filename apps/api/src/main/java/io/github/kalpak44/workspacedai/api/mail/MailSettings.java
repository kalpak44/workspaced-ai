package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where mail is sent from")
public record MailSettings(
    @Schema(
            description = "Mail goes out as any address on this domain",
            example = "workspaced.online")
        String domain,
    @Schema(
            description = "The address the platform's own notifications come from",
            example = "noreply@workspaced.online")
        String noReply,
    @Schema(
            description =
                "Whether a Mailjet account is configured; without one, sending answers 503")
        boolean configured) {}
