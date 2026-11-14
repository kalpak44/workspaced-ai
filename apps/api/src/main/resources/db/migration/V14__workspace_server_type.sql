-- The VM size a workspace was created as. Null for every workspace from before VMs, and locally,
-- where nothing runs.
ALTER TABLE workspace ADD COLUMN server_type VARCHAR(20);
