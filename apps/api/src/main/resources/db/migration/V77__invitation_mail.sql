-- Each invitation mail sent, by whom and when, so one user can send only so many a day. Kept apart
-- from workspace_invitation because withdrawing an invitation deletes its row, which would free
-- the allowance to send another.
CREATE TABLE invitation_mail (
    id      BIGSERIAL   PRIMARY KEY,
    inviter UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX invitation_mail_inviter_sent_at ON invitation_mail (inviter, sent_at);
