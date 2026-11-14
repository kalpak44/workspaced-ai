-- Markdown instructions users share and apply to their workspaces. A private recipe is seen by its
-- author alone; published_at is set the first time it turns public and kept after, so a recipe
-- made private and public again keeps its place among the newest.
CREATE TABLE recipe (
    id           UUID PRIMARY KEY,
    author_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title        VARCHAR(100) NOT NULL,
    summary      VARCHAR(300) NOT NULL DEFAULT '',
    category     VARCHAR(20)  NOT NULL
        CHECK (category IN ('apps', 'websites', 'automation', 'data', 'ai', 'devops', 'other')),
    tags         TEXT[]       NOT NULL DEFAULT '{}',
    content      TEXT         NOT NULL,
    visibility   VARCHAR(10)  NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX recipe_visibility_updated ON recipe (visibility, updated_at DESC);
CREATE INDEX recipe_author ON recipe (author_id, visibility);

-- One rating per user and recipe; rating again replaces it.
CREATE TABLE recipe_rating (
    recipe_id UUID     NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    user_id   UUID     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    stars     SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    PRIMARY KEY (recipe_id, user_id)
);
CREATE INDEX recipe_rating_user ON recipe_rating (user_id);
