ALTER TABLE workspace
 ADD COLUMN configuration_revision bigint NOT NULL DEFAULT 0,
 ADD COLUMN provider_start_config_revision bigint NOT NULL DEFAULT 0,
 ADD COLUMN pending_connector_id uuid REFERENCES ai_connector(id),
 ADD COLUMN provider_change_phase varchar(20) NOT NULL DEFAULT 'NONE',
 ADD COLUMN provider_revision bigint NOT NULL DEFAULT 0,
 ADD COLUMN provider_change_deadline timestamptz,
 ADD COLUMN provider_target_key text,
 ADD COLUMN provider_previous_key text,
 ADD COLUMN provider_change_error varchar(100);
