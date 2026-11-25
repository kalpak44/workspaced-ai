# Recipe authors

Users who publish a recipe earn balance credit when other people use it in a workspace they pay for. It gives every
user a way to earn by making something useful rather than by sharing a link, and each new public recipe is another
landing page for [11-recipe-pages.md](11-recipe-pages.md).

## Reward

**5% of the paid usage of each workspace attributed to the author's recipe, for 12 months, as credit**, capped at €25
per workspace. Authors who are approved partners under [14-partners.md](14-partners.md) earn it as cash instead, at the
partner rate's terms.

Nothing is paid for applying a recipe, rating it or running it in a demo, so publishing many recipes or applying them
from fake accounts earns nothing.

## What the platform keeps per €1 spent in an attributed workspace

| | Worst case | Typical | With Hetzner's VAT ID, worst case |
|---|---|---|---|
| Margin (see [economics.md](economics.md)) | €0.116 | €0.403 | €0.248 |
| Author credit (5% × cost share) | −€0.040 | −€0.028 | −€0.033 |
| **Platform keeps** | **+€0.076** | **+€0.375** | **+€0.215** |

On the same paid euro as a running referral (20% credit, −€0.158 worst case) it loses about €0.08 in today's worst
case; with the VAT ID the two together keep about +€0.08. The startup guard in [economics.md](economics.md) adds
this program to the others and decides.

## Attribution

A recipe is applied to a running workspace as a new session (`startRecipeSession` in
`apps/web/src/hooks/useRecipes.ts`), which queues the recipe as its first prompt.

- **Done: each application is recorded.** Applying sends `POST /workspaces/{id}/sessions?recipe=<id>`, and
  `recipe_use` keeps one row per recipe and workspace, with the user and when it was first applied. The store shows
  the count as `uses`. An author's own workspaces, private recipes and revisions are not counted. The row outlives
  the workspace, so a use can still be matched against what its owner paid.
- **One recipe per workspace, set once:** a workspace is attributed to the first public recipe by another author applied
  to it within 24 hours of its creation, or the recipe it was created from through the `?recipe=` deep link in
  [11-recipe-pages.md](11-recipe-pages.md). A workspace that has been running for weeks is not credited to whichever
  recipe was applied to it last.
- A workspace owned by the recipe's author, by an admin, or by a demo earns nothing.
- A revised recipe is a new recipe with a new author (see "Revise with the agent" in `RecipePage.tsx`), so a copy
  earns for whoever published it, and the original keeps the workspaces already attributed to it.

## Only paid money earns

`UsageCharger` posts each hour as one `USAGE` entry against a single balance, which mixes Stripe-paid money with bonus,
referral and recipe credit. Rewards need to know how much of an hour was paid:

- Keep a `paid_micros` figure on `billing_account` beside the balance: top-ups add to it, and each hour spends credit
  first and paid money after, so a reward is never paid on credit.
- The hour's paid share is what earns. This is the same split [14-partners.md](14-partners.md) needs for commission on
  `USAGE`, so build it once, here or there, whichever ships first.

## API

- **Migration:**
  - `recipe_use` (done, see above)
  - `recipe_attribution` (workspace_id PK, recipe_id, author_id, attributed_at, earning_until, earned_micros)
- **Ledger kinds:** `RECIPE_REWARD` and `RECIPE_REVERSAL`, posted in the same transaction as the hour they come from,
  with that hour's `USAGE` reference so a retried charge pays once.
- **Refunds:** a refunded top-up reverses the rewards it funded, through the refund handling
  [13-referrals.md](13-referrals.md) adds.
- **`GET /account/recipes/earnings`:** per recipe, workspaces using it, earned so far and this month. No names of the
  people using it.
- **Config:** `workspaced.recipes.rewards` with `enabled`, `percent`, `months` and `cap`; validated by the guard.
- **Notification:** a monthly `RECIPE_REWARD` summary rather than one per hour.

## Pages

- **Store (done):** the use count on each `RecipeCard` and on the recipe page, which is social proof on its own.
- **Recipe page, for its author:** workspaces using it, earned so far, and a share link carrying the author's referral
  or partner code.
- **General settings → Referrals:** a "Your recipes" row in the tiles, so all of a user's earnings are in one place.
- **Publishing a recipe:** one line saying it earns 5% of what others spend running it.

## Rules

- Credit cannot be withdrawn or transferred, as with referrals.
- Admins curate the store: a recipe that is spammy, unsafe or a copy made only to earn can be unpublished, and its
  attributions end.
- One reward per paid euro from this program, even when several recipes were applied to the workspace.

## Outside the API and web app

- `apps/site/src/terms.html`: the program and the right to unpublish.
- `apps/site/src/privacy.html`: we record which recipe a session was started from.

## Open questions

- How bonus credit is treated for VAT; the same question as [13-referrals.md](13-referrals.md).
