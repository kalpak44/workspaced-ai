-- Workspaces made before providers existed were all meant for OpenRouter; the default only
-- backfills them, so a new row has to name its provider.
ALTER TABLE workspace ADD COLUMN model_provider VARCHAR(50) NOT NULL DEFAULT 'openrouter';
ALTER TABLE workspace ALTER COLUMN model_provider DROP DEFAULT;

-- Names only: every value lives in the workspace's Kubernetes Secret and nowhere else.
CREATE TABLE workspace_secret (
    id           UUID PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);
