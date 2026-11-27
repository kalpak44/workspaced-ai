-- Every recipe Ciela's publisher wrote is published by workspaced, so they can no longer delete
-- it; Ciela itself stays theirs. Before Ciela is claimed its author is already workspaced, so
-- nothing changes.
UPDATE recipe
SET author_id = '00000000-0000-4000-8000-000000000a01',
    author_first_name = 'workspaced',
    author_last_name = ''
WHERE author_id = (SELECT author_id FROM recipe WHERE id = '00000000-0000-4000-8000-00000000b008')
  AND id <> '00000000-0000-4000-8000-00000000b008';
