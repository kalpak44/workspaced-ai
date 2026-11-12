-- A backup of one workspace's home and settings. The archive is in object storage under
-- object_key; this row is what lists it. workspace_id has no foreign key on purpose: a backup
-- outlives its workspace, which is how a deleted one is brought back.
CREATE TABLE workspace_backup (
    id             UUID PRIMARY KEY,
    owner_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    workspace_id   UUID         NOT NULL,
    -- The name when the backup was made, shown once the workspace itself is gone.
    workspace_name VARCHAR(100) NOT NULL,
    object_key     VARCHAR(300) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    phase          VARCHAR(20)  NOT NULL,
    progress       INT          NOT NULL DEFAULT 0,
    size_bytes     BIGINT,
    error          VARCHAR(500),
    -- The settings the archive does not hold, as JSON: model, session TTL, secret names and
    -- the session index.
    manifest       TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ
);

CREATE INDEX workspace_backup_owner_id ON workspace_backup (owner_id, created_at DESC);

-- Two backups of one workspace at once would share the harness's one job file.
CREATE UNIQUE INDEX workspace_backup_one_running ON workspace_backup (workspace_id)
    WHERE status = 'RUNNING';
