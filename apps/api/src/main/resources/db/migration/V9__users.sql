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

-- The default account, admin/admin; its password is meant to be changed after the first sign-in.
INSERT INTO app_user (id, username, password_hash)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin',
        '{bcrypt}$2a$10$BakxDObqdCeh/EHgPpyXluazI0lBzdFPRLJaS0WTPbkcFqlfqopfm');

-- Every workspace belongs to one user, and everything in it (sessions, secrets) follows from
-- the workspace. Workspaces made before accounts existed go to the default account.
ALTER TABLE workspace ADD COLUMN owner_id UUID REFERENCES app_user (id) ON DELETE CASCADE;
UPDATE workspace SET owner_id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE workspace ALTER COLUMN owner_id SET NOT NULL;

CREATE INDEX workspace_owner_id ON workspace (owner_id, created_at);
