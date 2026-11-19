-- The accounts that sign in. The password is a Spring Security encoded hash, prefixed with its
-- algorithm ({bcrypt}…) so a stronger one can be introduced without a migration.
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    first_name    VARCHAR(100) NOT NULL DEFAULT '',
    last_name     VARCHAR(100) NOT NULL DEFAULT '',
    -- A data: URL of the picture itself, at most 2 MB before encoding; null shows the default.
    avatar        TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every workspace belongs to one user, and everything in it (sessions, secrets) follows from
-- the workspace.
ALTER TABLE workspace ADD COLUMN owner_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;

CREATE INDEX workspace_owner_id ON workspace (owner_id, created_at);
