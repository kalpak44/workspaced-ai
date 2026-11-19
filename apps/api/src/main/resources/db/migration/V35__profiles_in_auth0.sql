-- A user's email, name and picture live in Auth0 alone. The app keeps who a user is, the Auth0
-- subject, and what they may do, the role, and asks Auth0 for the rest when it needs it.
ALTER TABLE app_user DROP COLUMN email;
ALTER TABLE app_user DROP COLUMN first_name;
ALTER TABLE app_user DROP COLUMN last_name;
ALTER TABLE app_user DROP COLUMN avatar;
