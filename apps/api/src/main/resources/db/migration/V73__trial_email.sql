-- The free demo and the trial AI key go to one inbox, not to one account: aliases of an address
-- (a +tag, Gmail's dots) hash alike. The hash is keyed, so the table holds no address.
ALTER TABLE app_user ADD COLUMN free_trial BOOLEAN NOT NULL DEFAULT true;

-- Kept when its account is deleted, so deleting one does not hand the address a second trial.
CREATE TABLE trial_email (
    email_hash VARCHAR(64) PRIMARY KEY,
    user_id    UUID        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
