CREATE TABLE workspace (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    -- Listing order: workspaces appear in the order they were made.
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
