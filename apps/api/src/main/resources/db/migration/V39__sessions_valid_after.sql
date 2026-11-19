-- Signing out revokes every session token issued before it, on every device: a token is
-- refused once it was issued before this time. Tokens are stateless otherwise, so one copied
-- elsewhere stayed valid until it expired.
ALTER TABLE app_user ADD COLUMN sessions_valid_after TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01T00:00:00Z';
