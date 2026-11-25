-- Users who asked to be emailed once a plan can be created again, while every regular plan was
-- sold out. A row is removed when its email is sent, so each request is answered once.
CREATE TABLE plan_waiter (
    user_id    UUID        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
