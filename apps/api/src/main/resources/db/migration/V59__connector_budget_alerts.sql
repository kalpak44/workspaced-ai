ALTER TABLE ai_connector ADD COLUMN status_checked_at TIMESTAMPTZ;
ALTER TABLE ai_connector ADD COLUMN observed_expires_at TIMESTAMPTZ;
ALTER TABLE ai_connector ADD COLUMN budget_notice_cycle VARCHAR(200);
ALTER TABLE ai_connector ADD COLUMN budget_notice_level INTEGER NOT NULL DEFAULT 0;
CREATE INDEX ai_connector_status_check ON ai_connector(status_checked_at NULLS FIRST, id);
