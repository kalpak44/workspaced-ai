CREATE TABLE workspace_web_domain (
    name VARCHAR(59) PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT false,
    primary_domain BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX workspace_web_domain_workspace ON workspace_web_domain(workspace_id);
CREATE UNIQUE INDEX workspace_web_domain_primary ON workspace_web_domain(workspace_id) WHERE primary_domain;
INSERT INTO workspace_web_domain (name, workspace_id, enabled, primary_domain)
SELECT web_server_name, id, true, true FROM workspace WHERE web_server_name IS NOT NULL;
-- Keep the historical column for the old API pod during a rolling upgrade.
