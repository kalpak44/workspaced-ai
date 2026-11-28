package io.github.kalpak44.workspacedai.api.notification;

import java.util.UUID;

/** A notification just sent that its user is to get by mail as well, once it has committed. */
record NotificationMail(UUID id, UUID user, NotificationMessage message) {}
