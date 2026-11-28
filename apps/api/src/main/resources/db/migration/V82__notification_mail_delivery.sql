-- Existing notifications were already processed; only new mail intents enter the outbox.
ALTER TABLE notification ADD COLUMN mail_pending boolean NOT NULL DEFAULT false;
ALTER TABLE notification ADD COLUMN mail_next_attempt_at timestamptz;
ALTER TABLE notification ADD COLUMN mail_attempts integer NOT NULL DEFAULT 0;
CREATE INDEX notification_mail_due ON notification(mail_next_attempt_at) WHERE mail_pending;
