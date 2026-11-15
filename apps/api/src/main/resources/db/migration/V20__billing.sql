-- What each account has to spend. Micro-units of the billing currency rather than cents, so a
-- charge for a fraction of a cent can be recorded exactly once usage is billed.
CREATE TABLE billing_account (
    user_id            UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    balance_micros     BIGINT       NOT NULL DEFAULT 0,
    -- The Stripe customer every checkout and invoice of this account is filed under.
    stripe_customer_id VARCHAR(255) UNIQUE
);

-- Every change to a balance, never updated or deleted: the balance is always their sum. The
-- unique reference turns a retried credit, from a redelivered webhook say, into a no-op.
CREATE TABLE billing_ledger_entry (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    amount_micros BIGINT       NOT NULL,
    kind          VARCHAR(20)  NOT NULL,
    reference     VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (kind, reference)
);

CREATE INDEX billing_ledger_entry_user ON billing_ledger_entry (user_id, created_at);

-- A payment started through Stripe Checkout. Stripe issues the invoice once it is paid, and is
-- where the invoice itself lives; only its id and number are kept here.
CREATE TABLE billing_top_up (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    amount_cents        BIGINT       NOT NULL CHECK (amount_cents > 0),
    status              VARCHAR(10)  NOT NULL,
    checkout_session_id VARCHAR(255) UNIQUE,
    stripe_invoice_id   VARCHAR(255),
    invoice_number      VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    paid_at             TIMESTAMPTZ
);

CREATE INDEX billing_top_up_user ON billing_top_up (user_id, created_at);
