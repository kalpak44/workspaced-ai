-- Users a workspace's owner, or one of its collaborators, invited into it. Ids only: their
-- addresses stay in Auth0.
CREATE TABLE workspace_collaborator (
    workspace_id UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    invited_by   UUID        REFERENCES app_user (id) ON DELETE SET NULL,
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX workspace_collaborator_user ON workspace_collaborator (user_id);

-- An invitation not yet accepted. The address is kept only as a keyed hash, enough to check that
-- whoever accepts signed in with it, and the code only as a hash, so a copy of this table opens
-- nothing. A row is deleted when it is accepted; one past expires_at is never accepted.
CREATE TABLE workspace_invitation (
    id           UUID        PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    email_hash   VARCHAR(64) NOT NULL,
    code_hash    VARCHAR(64) NOT NULL UNIQUE,
    invited_by   UUID        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (workspace_id, email_hash)
);

-- Who started a chat session: only they see it, even in a shared workspace. A scheduled task's
-- runs belong to the workspace and are shown to everyone on it. Existing sessions were all
-- started by their workspace's owner.
ALTER TABLE workspace_session ADD COLUMN user_id UUID REFERENCES app_user (id) ON DELETE SET NULL;
UPDATE workspace_session s SET user_id = w.owner_id
FROM workspace w WHERE w.id = s.workspace_id AND s.task IS NULL;
CREATE INDEX workspace_session_user_id ON workspace_session (user_id);
