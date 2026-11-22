-- Existing connectors predate this notice. Do not fabricate an acknowledgement for them.
ALTER TABLE ai_connector ADD COLUMN provider_notice_accepted_at TIMESTAMPTZ;
ALTER TABLE ai_connector ADD COLUMN provider_notice_version VARCHAR(100);
