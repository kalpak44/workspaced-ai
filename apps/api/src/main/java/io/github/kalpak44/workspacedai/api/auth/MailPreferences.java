package io.github.kalpak44.workspacedai.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which of the platform's mail the user gets; what they cannot turn off is not listed. In an
 * update, a field left out keeps its current value.
 */
@Schema(description = "Which of the platform's mail the account gets")
public record MailPreferences(
    @Schema(
            description =
                "Whether the balance reminders before a workspace is paused are mailed; a pause,"
                    + " a deletion and a failed automatic top-up are mailed regardless")
        Boolean balanceReminders,
    @Schema(
            description =
                "Whether the occasional tips are mailed: starting the free trial, what to do after"
                    + " it, inviting friends, and a balance left unused")
        Boolean tips) {}
