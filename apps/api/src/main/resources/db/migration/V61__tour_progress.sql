-- Where the account's guided tour is, so it resumes in any browser instead of starting again with
-- a second free demo. Null while no tour is under way. Only the web app reads it: the API never
-- trusts its ids, which are checked against the owner wherever they are used.
ALTER TABLE app_user ADD COLUMN tour_step INTEGER;
ALTER TABLE app_user ADD COLUMN tour_workspace_id VARCHAR(100);
ALTER TABLE app_user ADD COLUMN tour_session_id VARCHAR(100);
ALTER TABLE app_user ADD COLUMN tour_draft_id VARCHAR(100);
ALTER TABLE app_user ADD COLUMN tour_name VARCHAR(100);
ALTER TABLE app_user ADD COLUMN tour_paused BOOLEAN NOT NULL DEFAULT FALSE;
