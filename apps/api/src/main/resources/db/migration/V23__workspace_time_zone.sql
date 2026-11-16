-- The IANA zone the workspace's agent reads times in, handed to its harness as USER_TIMEZONE.
ALTER TABLE workspace ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';
