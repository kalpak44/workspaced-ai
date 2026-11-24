-- Whether the balance reminders that come before a pause are mailed as well as shown in the
-- app. A pause, a deletion and a failed automatic top-up are mailed regardless.
ALTER TABLE app_user ADD COLUMN balance_reminder_mail BOOLEAN NOT NULL DEFAULT TRUE;
