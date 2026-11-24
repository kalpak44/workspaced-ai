-- Every workspace so far runs on Hetzner; the default only backfills them, so a new row has to
-- name its provider.
ALTER TABLE workspace ADD COLUMN compute_provider VARCHAR(50) NOT NULL DEFAULT 'hetzner';
ALTER TABLE workspace ALTER COLUMN compute_provider DROP DEFAULT;
