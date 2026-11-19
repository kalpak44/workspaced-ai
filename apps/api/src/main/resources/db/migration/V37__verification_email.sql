-- When each unverified sign-up last had its verification email sent again, so a script cannot
-- send it in a loop through the platform's mail. Keyed by the Auth0 subject, which is all the app
-- knows about someone who has not signed in yet.
CREATE TABLE verification_email (
    idp_subject VARCHAR(255) PRIMARY KEY,
    sent_at     TIMESTAMPTZ  NOT NULL
);
