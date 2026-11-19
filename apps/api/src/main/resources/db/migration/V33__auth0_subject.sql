-- Auth0 proves who signs in, so no password is kept here. Each account is tied to its Auth0
-- user by subject; an account made before Auth0 has none until its owner first signs in, and is
-- then matched by verified email.
ALTER TABLE app_user ADD COLUMN idp_subject VARCHAR(255) UNIQUE;
ALTER TABLE app_user DROP COLUMN password_hash;
