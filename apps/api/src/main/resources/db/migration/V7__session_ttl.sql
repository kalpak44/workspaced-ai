ALTER TABLE workspace
    ADD COLUMN session_ttl_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN session_ttl_hours INTEGER NOT NULL DEFAULT 168
        CHECK (session_ttl_hours IN (1, 6, 24, 72, 168, 720));
