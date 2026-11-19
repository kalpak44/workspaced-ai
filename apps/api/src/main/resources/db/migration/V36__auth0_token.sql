-- The token the API calls Auth0's Management API with, shared by every instance so the API keeps
-- no state of its own. One row; a refresh replaces it. It reads and updates users, and expires
-- within a day.
CREATE TABLE auth0_token (
    id           SMALLINT    PRIMARY KEY CHECK (id = 1),
    access_token TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);
