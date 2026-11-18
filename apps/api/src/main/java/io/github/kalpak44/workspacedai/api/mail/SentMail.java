package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A message Mailjet accepted for delivery")
public record SentMail(
    @Schema(
            description = "Mailjet's id for the message, to look it up there",
            example = "1152921529051316483")
        String id) {}
