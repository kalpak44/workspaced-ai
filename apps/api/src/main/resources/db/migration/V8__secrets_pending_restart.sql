-- Set when a secret is added or removed, cleared by a restart: the pod reads its secrets only
-- when it starts, so until then the change is saved but not applied.
ALTER TABLE workspace
    ADD COLUMN secrets_pending_restart BOOLEAN NOT NULL DEFAULT false;
