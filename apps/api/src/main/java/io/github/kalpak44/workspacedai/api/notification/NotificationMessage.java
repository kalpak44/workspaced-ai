package io.github.kalpak44.workspacedai.api.notification;

/**
 * What a notification says, before it is sent to anyone.
 *
 * @param summary one line for the menu and the list
 * @param body the full text, shown on the notification's own page
 * @param action null when there is nowhere to go
 */
public record NotificationMessage(
    NotificationKind kind,
    NotificationTone tone,
    String title,
    String summary,
    String body,
    NotificationAction action) {}
