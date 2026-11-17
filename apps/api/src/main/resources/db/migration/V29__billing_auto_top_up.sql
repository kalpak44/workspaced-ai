-- The card saved through a Checkout in setup mode, charged without the user present when the
-- balance drops below the threshold the user chose. Only the Stripe payment method's id and a
-- label to show it by are kept; Stripe keeps the card itself.
ALTER TABLE billing_account
    ADD COLUMN payment_method_id        VARCHAR(255),
    ADD COLUMN card_label               VARCHAR(100),
    ADD COLUMN auto_top_up_enabled      BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN auto_top_up_below_cents  BIGINT,
    ADD COLUMN auto_top_up_amount_cents BIGINT;

-- Charged to the saved card by the app rather than paid at a checkout.
ALTER TABLE billing_top_up ADD COLUMN automatic BOOLEAN NOT NULL DEFAULT false;
