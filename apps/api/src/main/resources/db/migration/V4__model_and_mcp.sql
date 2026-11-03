-- The model the workspace's agent replies with; null keeps the harness's own default.
ALTER TABLE workspace ADD COLUMN model VARCHAR(200);

-- Remote MCP servers the workspace's agent can use. A header value may reference a workspace
-- secret as {env:NAME}, which the harness substitutes, so a token need never be stored here.
CREATE TABLE workspace_mcp_server (
    id           UUID PRIMARY KEY,
    workspace_id UUID          NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    name         VARCHAR(64)   NOT NULL,
    url          VARCHAR(2000) NOT NULL,
    headers      JSONB         NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, name)
);
