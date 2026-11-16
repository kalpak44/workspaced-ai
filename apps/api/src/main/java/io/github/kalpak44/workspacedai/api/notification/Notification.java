package io.github.kalpak44.workspacedai.api.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Something the app told the user")
public record Notification(
    UUID id,
    NotificationKind kind,
    NotificationTone tone,
    @Schema(example = "Payment received") String title,
    @Schema(description = "One line for the menu and the list") String summary,
    @Schema(description = "The full text") String body,
    Instant createdAt,
    boolean read,
    @Schema(nullable = true) NotificationAction action) {}
