-- What the app tells a user: a payment, a workspace made or deleted, a balance running low.
-- The action is a path in the web app, e.g. /billing. A reminder repeated daily carries a key,
-- so it is sent once per key however often its job runs.
CREATE TABLE notification (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind         VARCHAR(20)  NOT NULL,
    tone         VARCHAR(10)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    summary      VARCHAR(500) NOT NULL,
    body         TEXT         NOT NULL,
    action_label VARCHAR(100),
    action_path  VARCHAR(500),
    dedupe_key   VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at      TIMESTAMPTZ,
    UNIQUE (user_id, dedupe_key)
);

CREATE INDEX notification_newest ON notification (user_id, created_at DESC);
