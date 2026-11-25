-- Every built-in recipe is published by workspaced, except Ciela, which StarterRecipes hands to
-- its publisher. Only the fixed seed ids match, so recipes users wrote keep their authors.
UPDATE recipe
SET author_id = '00000000-0000-4000-8000-000000000a01',
    author_first_name = 'workspaced',
    author_last_name = ''
WHERE id IN ('00000000-0000-4000-8000-00000000b001',
             '00000000-0000-4000-8000-00000000b005',
             '00000000-0000-4000-8000-00000000b006',
             '00000000-0000-4000-8000-00000000b007',
             '00000000-0000-4000-8000-00000000b009',
             '00000000-0000-4000-8000-00000000b00a',
             '00000000-0000-4000-8000-00000000b00b',
             '00000000-0000-4000-8000-00000000b00c',
             '00000000-0000-4000-8000-00000000b00d');
