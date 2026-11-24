-- Each stretch of time no plan could be created, so admins hear of it once and the hours it
-- cost can be counted. At most one is open, since the job that writes them runs on one instance.
CREATE TABLE plan_outage (
    id BIGSERIAL PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    alerted_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX plan_outage_open ON plan_outage ((ended_at IS NULL)) WHERE ended_at IS NULL;
