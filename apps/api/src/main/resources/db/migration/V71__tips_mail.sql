-- Whether the occasional tips are mailed: starting the free trial, what to do after it, inviting
-- friends, and a balance left unused. They stay in the app either way.
ALTER TABLE app_user ADD COLUMN tips_mail BOOLEAN NOT NULL DEFAULT TRUE;
