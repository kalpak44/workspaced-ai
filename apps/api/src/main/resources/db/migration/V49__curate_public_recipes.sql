-- Retire the three starter examples and keep the maintained Telegram bot recipe.
DELETE FROM recipe
WHERE id IN ('00000000-0000-4000-8000-00000000b002',
             '00000000-0000-4000-8000-00000000b003',
             '00000000-0000-4000-8000-00000000b004')
   OR (title = 'Telegram bot for your workspace'
       AND visibility = 'PUBLIC'
       AND id <> '00000000-0000-4000-8000-00000000b009');

-- Public catalogue recipes belong to the platform; Ciela remains its publisher's private recipe.
UPDATE recipe
SET author_id = '00000000-0000-4000-8000-000000000a01',
    author_first_name = 'workspaced', author_last_name = ''
WHERE visibility = 'PUBLIC';
