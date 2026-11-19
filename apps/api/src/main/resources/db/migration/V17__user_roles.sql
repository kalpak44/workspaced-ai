-- An admin also manages the platform; everyone else reaches only their own workspaces.
ALTER TABLE app_user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
    CHECK (role IN ('USER', 'ADMIN'));

-- Carried in every token and bumped when the password changes, so older tokens stop working.
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
