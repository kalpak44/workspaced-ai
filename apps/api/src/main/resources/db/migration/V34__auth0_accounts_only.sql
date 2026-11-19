-- Every account comes from an Auth0 sign-in and is keyed by its Auth0 subject. Accounts made
-- before Auth0 are removed with everything they owned rather than carried across; their
-- workspaces' VMs are deleted through the app before this runs.
DELETE FROM app_user;
ALTER TABLE app_user ALTER COLUMN idp_subject SET NOT NULL;
-- The role comes from Auth0 at each sign-in, and a deleted account is refused by its absence, so
-- no version is needed to revoke a token.
ALTER TABLE app_user DROP COLUMN token_version;
