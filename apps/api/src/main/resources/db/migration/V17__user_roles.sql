-- An admin also manages the other users; everyone else reaches only their own workspaces.
-- Only the default account starts as an admin.
ALTER TABLE app_user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
    CHECK (role IN ('USER', 'ADMIN'));
UPDATE app_user SET role = 'ADMIN' WHERE id = '00000000-0000-0000-0000-000000000001';

-- Carried in every token and bumped when the password changes, so older tokens stop working.
ALTER TABLE app_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
