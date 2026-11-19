-- The admin every test signs in as. A real one comes from an Auth0 sign-in with the admin role,
-- which no test reaches.
INSERT INTO app_user (id, idp_subject, email, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'test|admin', 'admin@example.com', 'ADMIN')
ON CONFLICT DO NOTHING;
