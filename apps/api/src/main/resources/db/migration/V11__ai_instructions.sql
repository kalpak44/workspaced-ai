-- Markdown the agent is given in every session. A user's general instructions apply to all their
-- workspaces; a workspace's own come after them. The API writes both into the workspace's home,
-- where the harness reads them, whenever they change and whenever the workspace starts.
ALTER TABLE app_user ADD COLUMN ai_instructions TEXT NOT NULL DEFAULT '';
ALTER TABLE workspace ADD COLUMN ai_instructions TEXT NOT NULL DEFAULT '';
