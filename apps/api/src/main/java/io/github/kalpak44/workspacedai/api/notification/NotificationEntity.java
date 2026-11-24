package io.github.kalpak44.workspacedai.api.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One notification, gone with the user it was sent to. */
@Entity
@Table(name = "notification")
class NotificationEntity {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private NotificationKind kind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private NotificationTone tone;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, length = 500)
  private String summary;

  @Column(nullable = false, columnDefinition = "text")
  private String body;

  @Column(name = "action_label", length = 100)
  private String actionLabel;

  @Column(name = "action_path", length = 500)
  private String actionPath;

  @Column(name = "dedupe_key", length = 200)
  private String dedupeKey;

  // Set when it was mailed as well, naming the milestone it was mailed for.
  @Column(name = "mail_key", length = 200)
  private String mailKey;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "read_at")
  private Instant readAt;

  protected NotificationEntity() {}

  NotificationEntity(UUID userId, NotificationMessage message, String dedupeKey) {
    this(userId, message, dedupeKey, null);
  }

  NotificationEntity(UUID userId, NotificationMessage message, String dedupeKey, String mailKey) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.kind = message.kind();
    this.tone = message.tone();
    this.title = message.title();
    this.summary = message.summary();
    this.body = message.body();
    if (message.action() != null) {
      this.actionLabel = message.action().label();
      this.actionPath = message.action().path();
    }
    this.dedupeKey = dedupeKey;
    this.mailKey = mailKey;
    this.createdAt = Instant.now();
  }

  /** Keeps the time it was first read, so reading it again changes nothing. */
  void markRead(boolean read) {
    if (!read) {
      readAt = null;
    } else if (readAt == null) {
      readAt = Instant.now();
    }
  }

  Notification view() {
    return new Notification(
        id,
        kind,
        tone,
        title,
        summary,
        body,
        createdAt,
        readAt != null,
        actionPath == null ? null : new NotificationAction(actionLabel, actionPath));
  }
}
