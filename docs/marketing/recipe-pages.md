# Recipe pages

Each public recipe — WordPress, a simple web page, the authenticated browser and the rest — is a ready-made search
landing page. People search for "WordPress with an AI agent" and "deploy X in two minutes"; a page per recipe answers
that.

## Recommendation

A static page per public recipe on `workspaced.online`, for example `/recipes/wordpress`, with:

- what it sets up and what it is for, in plain words
- a short screen recording or screenshots
- price per hour and per month of the size it needs
- the recipe's star rating
- a **"Try it free"** button that opens the app, signs up if needed, and starts the demo with that recipe selected

## Implementation

- **Generated at build time**, not by hand. A script in `apps/site/scripts` reads the public recipes from the API
  (or from `docs/recipes/*.md`, which already describes them) and writes one HTML page per recipe from a template, plus
  their entries in `sitemap.xml`. The site stays static HTML, which is what search engines index best.
- **Shared header and footer:** the template carries the same header, footer and privacy notice as the other pages.
- **Deep link:** `ai.workspaced.online/?recipe=<slug>&demo` — the web app keeps the parameter through sign-in and opens
  `CreateWorkspaceDialog` with the recipe and the demo preselected.
- `npm run check` already fails on broken local links, so a removed recipe cannot leave a dead page linked.

## Recipe authors

Recipe authors can join [partners.md](partners.md): a recipe started from its own page, or shared by its author, carries
the author's partner code. Authors then promote their own recipes.

## Measure

Search impressions and clicks per page (Google Search Console), and demos started per recipe page on the funnel from
[analytics.md](analytics.md).
