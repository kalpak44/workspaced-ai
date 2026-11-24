# Comparison pages

Transparent pricing is a selling point here, and comparison pages draw search traffic from people already choosing a
tool.

## Done

**Pricing page** (`0d46950`): `/pricing` on the site with the three plans, what is free, what others bill and how the
balance works. Its prices are written into the page, so a change to `workspaced.plans` is a change to `pricing.html`
too. Top-up bonuses from [08-top-up-bonuses.md](08-top-up-bonuses.md) go on it once they exist.

## Comparison pages

One page each, for example `/compare/codespaces`:

- GitHub Codespaces
- Replit
- cloud coding agents (hosted agent products)
- "a VPS you set up yourself"

Each page: a short honest table (price for a comparable machine, what is included, where the agent runs, lock-in), when
the alternative is the better choice, and a "Try it free" button. Honesty is what makes these pages trusted and linked;
a page that only praises us does neither.

## Maintenance

Competitor prices change. Put a "checked on" date on each page and review them every quarter. Add the pages to
`sitemap.xml`; `npm run check` catches broken links between them.
