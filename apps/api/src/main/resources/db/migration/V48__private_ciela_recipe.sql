-- Keep the seeded Ciela recipe for its publisher, claimed after a verified sign-in.
UPDATE recipe
SET visibility = 'PRIVATE', published_at = NULL
WHERE id = '00000000-0000-4000-8000-00000000b008';
