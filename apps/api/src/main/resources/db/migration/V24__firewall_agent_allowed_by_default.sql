-- The agent manages the firewall unless the user turns it off; it still asks before opening a port.
ALTER TABLE workspace ALTER COLUMN firewall_agent_allowed SET DEFAULT true;
UPDATE workspace SET firewall_agent_allowed = true;
