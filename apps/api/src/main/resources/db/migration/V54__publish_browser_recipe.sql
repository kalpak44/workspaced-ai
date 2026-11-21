-- The shared-browser recipe belongs to the public Workspaced catalogue.
UPDATE recipe
SET visibility = 'PUBLIC',
    published_at = COALESCE(published_at, now()),
    author_id = '00000000-0000-4000-8000-000000000a01',
    author_first_name = 'workspaced',
    author_last_name = ''
WHERE id = '00000000-0000-4000-8000-00000000b00c';
