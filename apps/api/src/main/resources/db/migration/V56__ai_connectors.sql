CREATE TABLE ai_connector (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    encrypted_key TEXT NOT NULL
);
CREATE INDEX ai_connector_owner ON ai_connector(owner_id);
ALTER TABLE workspace ADD COLUMN ai_connector_id UUID REFERENCES ai_connector(id) ON DELETE SET NULL;
CREATE INDEX workspace_ai_connector ON workspace(ai_connector_id);
