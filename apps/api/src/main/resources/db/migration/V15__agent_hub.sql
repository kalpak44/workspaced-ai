-- Read and written by the agent hub, not the API: it shares this database and leaves its schema to
-- these migrations. Only a hash of each enrollment token is kept, so a database read cannot enroll.
create table enrollment_token (vm_id uuid primary key, token_hash bytea not null, expires_at timestamptz not null, created_at timestamptz not null default now());
create table agent (vm_id uuid primary key, public_key bytea not null, enrolled_at timestamptz not null default now(), last_seen_at timestamptz);
