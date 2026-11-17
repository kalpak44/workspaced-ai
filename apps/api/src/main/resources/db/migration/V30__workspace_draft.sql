-- Set while a workspace waits for its provider key: it has an id, which the key can be labelled
-- with, but no VM and no charge. Launching clears it; a draft nobody launches is deleted.
ALTER TABLE workspace ADD COLUMN draft BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX workspace_draft_created ON workspace (created_at) WHERE draft;
