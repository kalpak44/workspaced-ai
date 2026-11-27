ALTER TABLE app_user ADD COLUMN ai_credit_warning_usd numeric(8,2) NOT NULL DEFAULT 1.00 CHECK (ai_credit_warning_usd >= 0 AND ai_credit_warning_usd <= 100000);
