-- A reminder repeated daily is mailed only at its milestones, such as three days left. The key
-- names the milestone it was mailed for, so the next days' reminders stay in the app.
ALTER TABLE notification ADD COLUMN mail_key VARCHAR(200);

CREATE INDEX notification_mail_key ON notification (user_id, mail_key) WHERE mail_key IS NOT NULL;
