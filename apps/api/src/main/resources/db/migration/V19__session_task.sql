-- The scheduled task a session was started for, so the app can list task runs apart from the
-- user's own sessions. Null for a session the user started; the harness may retitle either.
ALTER TABLE workspace_session ADD COLUMN task VARCHAR(63);
