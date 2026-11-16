-- What a workspace costs its owner an hour, fixed when it was created, and the end of the last
-- hour charged for. Admins' workspaces have none, and a deleted workspace's goes with it.
CREATE TABLE billing_usage (
    workspace_id  UUID PRIMARY KEY REFERENCES workspace (id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    hourly_micros BIGINT      NOT NULL CHECK (hourly_micros > 0),
    charged_until TIMESTAMPTZ NOT NULL
);

CREATE INDEX billing_usage_due ON billing_usage (charged_until);
