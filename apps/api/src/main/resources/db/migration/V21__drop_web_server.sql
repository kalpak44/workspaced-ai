-- Workspaces publish their own ports on the VM's address; the gateway and its names are gone.
DROP TABLE workspace_web_domain;
ALTER TABLE workspace
    DROP COLUMN web_server_name,
    DROP COLUMN web_server_agent_allowed;
