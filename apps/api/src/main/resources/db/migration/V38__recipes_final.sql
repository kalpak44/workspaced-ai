-- A recipe never changes once created, so its ratings always refer to what was published. Its
-- author's name is kept with it, as the author had it then, so listing recipes asks Auth0 for
-- nothing; recipes made before this show their author as Unknown.
ALTER TABLE recipe ADD COLUMN author_first_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE recipe ADD COLUMN author_last_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE recipe DROP COLUMN updated_at;
