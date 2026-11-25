-- Which workspaces each recipe was applied in, once per workspace, so the store can say how many
-- use it. workspace_id has no foreign key: a deleted workspace still counts as one that used it.
-- An author's own workspaces are not recorded.
CREATE TABLE recipe_use (
    recipe_id     UUID        NOT NULL REFERENCES recipe (id) ON DELETE CASCADE,
    workspace_id  UUID        NOT NULL,
    user_id       UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    first_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (recipe_id, workspace_id)
);
CREATE INDEX recipe_use_user ON recipe_use (user_id);
