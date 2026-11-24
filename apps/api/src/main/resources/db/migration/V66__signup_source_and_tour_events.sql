-- Where a sign-up came from, as the link that brought it named it (utm_source, utm_campaign),
-- set once when the account is made. Null for an account made before, or with no such link.
ALTER TABLE app_user ADD COLUMN signup_source VARCHAR(100);
ALTER TABLE app_user ADD COLUMN signup_campaign VARCHAR(100);

-- Every move of the guided tour, appended and never updated, so the admin funnel can show where
-- people stop. app_user's tour columns stay the resume bookmark. A step is the web app's step
-- number, so renumbering the tour's steps mixes two steps in one count.
CREATE TABLE tour_event (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    step       INTEGER     NOT NULL,
    event      VARCHAR(10) NOT NULL CHECK (event IN ('REACHED', 'PAUSED', 'RESUMED', 'ENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tour_event_user ON tour_event (user_id, created_at);

-- The admin funnel picks accounts by when they were made.
CREATE INDEX app_user_created ON app_user (created_at);
