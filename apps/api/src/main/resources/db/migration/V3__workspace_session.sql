-- A chat session: our id, the directory it works in inside the pod, and the harness's own
-- id for it. The harness holds the conversation; this row is what makes it findable.
CREATE TABLE workspace_session (
    id                 UUID PRIMARY KEY,
    workspace_id       UUID         NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    path               VARCHAR(500) NOT NULL,
    harness_session_id VARCHAR(100) NOT NULL,
    -- Null until the harness names the session after its first exchange.
    title              VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX workspace_session_workspace_id ON workspace_session (workspace_id, created_at);
