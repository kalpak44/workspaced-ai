-- What a collaborator calls the workspace. Each one names it for themselves, starting from the
-- name it had when they joined, so neither the owner's renames nor theirs reach anyone else.
ALTER TABLE workspace_collaborator ADD COLUMN name VARCHAR(100);
UPDATE workspace_collaborator c SET name = w.name FROM workspace w WHERE w.id = c.workspace_id;
ALTER TABLE workspace_collaborator ALTER COLUMN name SET NOT NULL;
