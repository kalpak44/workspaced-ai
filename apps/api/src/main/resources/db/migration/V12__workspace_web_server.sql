-- The name a workspace's web server is public under, as <name>.<domain>; null while it is off.
-- Unique, so two workspaces asking for one name at the same moment cannot both get it.
-- web_server_agent_allowed lets the workspace's own agent turn it on, off and rename it.
ALTER TABLE workspace
    ADD COLUMN web_server_name VARCHAR(63) UNIQUE,
    ADD COLUMN web_server_agent_allowed BOOLEAN NOT NULL DEFAULT false;
