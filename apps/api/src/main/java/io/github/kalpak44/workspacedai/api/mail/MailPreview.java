package io.github.kalpak44.workspacedai.api.mail;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A message as its reader would see it, sent nowhere")
public record MailPreview(
    @Schema(description = "The whole HTML part, with the logo inlined so a browser can show it")
        String html) {}
