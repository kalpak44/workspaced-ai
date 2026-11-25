-- Each user's code for their invitation link, made the first time they open their referrals.
CREATE TABLE referral_code (
    user_id    UUID        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    code       VARCHAR(12) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Who invited whom, bound once when the invited account is made. The totals are what each side
-- has been credited so far, kept here so the caps hold without summing the ledger. Ids only:
-- names stay in Auth0.
CREATE TABLE referral (
    referee_id      UUID        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    referrer_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    signed_up_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_paid_at   TIMESTAMPTZ,
    bonus_micros    BIGINT      NOT NULL DEFAULT 0,
    reward_micros   BIGINT      NOT NULL DEFAULT 0,
    CHECK (referee_id <> referrer_id)
);
CREATE INDEX referral_referrer ON referral (referrer_id, signed_up_at DESC);
