-- Whether the workspace's own agent may open and close ports on its VM's firewall.
ALTER TABLE workspace ADD COLUMN firewall_agent_allowed BOOLEAN NOT NULL DEFAULT false;
