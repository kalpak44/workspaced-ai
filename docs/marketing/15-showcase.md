# Showcase and social proof

Show what people build here. Visitors trust other users' results more than our description.

## Showcase gallery

An opt-in gallery on `workspaced.online/showcase` of sites and apps users built and published on their own domains.

- **Opt-in per workspace:** a "Show in the showcase" toggle with a title, one sentence and a screenshot, in the
  workspace's domains panel (`DomainsPanel`). Nothing is listed without it.
- **Admin approval** before anything appears, since the content is the user's.
- **Built at deploy time** into static HTML like [11-recipe-pages.md](11-recipe-pages.md), from `GET /showcase` (approved
  entries only), so the site stays static and indexable.
- **Credit:** each entry can name the recipe it started from, linking to that recipe's page.

## "Built with workspaced"

An optional small footer link users can add to what they publish, suggested by the agent when it sets up a site, never
added without asking. With a partner code it earns the user commission under [14-partners.md](14-partners.md).

## Social proof on the site

- **Live counters** on the home page: workspaces launched, recipes, hours run. Served by a cached public endpoint so the
  static site can fetch them.
- **Recipe ratings:** recipes already have star ratings; show them on recipe pages.
- **Quotes:** a feedback prompt after a user's first month ("How is it going?"); with permission, the best answers go on
  the home page with first name and project.

## Privacy

The gallery shows only what a user published publicly and chose to list. `privacy.html` and `terms.html` get a line on
the showcase and how to remove an entry.
