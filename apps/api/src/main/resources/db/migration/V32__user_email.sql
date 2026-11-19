-- Users sign in with an email instead of a username, so notifications can reach them. Stored
-- lowercased, so one address cannot exist twice in different capitalizations.
ALTER TABLE app_user RENAME COLUMN username TO email;
ALTER TABLE app_user ALTER COLUMN email TYPE VARCHAR(254);

UPDATE app_user SET email = lower(email);

ALTER TABLE app_user ADD CONSTRAINT app_user_email_lowercase CHECK (email = lower(email));
