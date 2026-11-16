package io.github.kalpak44.workspacedai.api.notification;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where a notification asks its reader to go next")
public record NotificationAction(
    @Schema(example = "Go to billing") String label,
    @Schema(description = "A path in the web app", example = "/billing") String path) {}
