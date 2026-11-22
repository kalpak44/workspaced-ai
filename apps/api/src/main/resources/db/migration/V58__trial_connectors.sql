ALTER TABLE ai_connector ADD COLUMN trial BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ai_connector ADD COLUMN provider_key_hash VARCHAR(200);
CREATE UNIQUE INDEX ai_connector_one_trial_per_user ON ai_connector(owner_id) WHERE trial;
