-- An archive the user uploaded in parts rather than one the workspace made. upload_id is the
-- storage's multipart upload those parts go into.
ALTER TABLE workspace_backup ADD COLUMN uploaded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace_backup ADD COLUMN upload_id VARCHAR(1024);
