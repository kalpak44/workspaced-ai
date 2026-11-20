-- The one free demo workspace each account gets: set when its VM is started, and cleared again
-- only when that start fails, so deleting the demo early does not hand out a second one.
ALTER TABLE app_user ADD COLUMN demo_started_at TIMESTAMPTZ;

-- A demo is never charged and is deleted at expires_at, whatever state it is in then.
ALTER TABLE workspace ADD COLUMN demo BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE workspace ADD COLUMN expires_at TIMESTAMPTZ;

CREATE INDEX workspace_demo_expires ON workspace (expires_at) WHERE demo;
