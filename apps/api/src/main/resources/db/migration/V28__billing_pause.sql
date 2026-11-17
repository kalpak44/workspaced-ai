-- Set while the workspace is paused because the balance could not pay its next hour: its VM is
-- shut down and it is not charged meanwhile. A top-up starts it again; otherwise it is deleted
-- once it has been paused for as long as billing allows.
ALTER TABLE billing_usage ADD COLUMN paused_at TIMESTAMPTZ;
