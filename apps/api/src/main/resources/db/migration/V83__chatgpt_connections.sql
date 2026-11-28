ALTER TABLE ai_connector ADD COLUMN auth_type VARCHAR(30) NOT NULL DEFAULT 'api_key';
ALTER TABLE ai_connector ADD COLUMN workspace_id UUID REFERENCES workspace(id) ON DELETE CASCADE;
CREATE INDEX ai_connector_workspace ON ai_connector(workspace_id);
CREATE TABLE chatgpt_login (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    encrypted_state TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_poll_at TIMESTAMP WITH TIME ZONE NOT NULL,
    poll_seconds INTEGER NOT NULL,
    connector_id UUID REFERENCES ai_connector(id) ON DELETE CASCADE
);
CREATE INDEX chatgpt_login_workspace ON chatgpt_login(workspace_id);
