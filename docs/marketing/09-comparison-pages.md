# Pricing and comparison pages

Transparent pricing is a selling point here, and comparison pages draw search traffic from people already choosing a
tool.

## Pricing page

A `pricing.html` on the site that says it plainly:

- **"Hetzner's price plus 35%, charged by the hour. No subscription."**
- A table of sizes with hourly and monthly prices, generated at build time from the API's `/vm-sizes` (the same prices
  users see), so it cannot drift from the app.
- What is free: one 4-hour demo, the trial AI key.
- What costs extra: the reactivation fee, AI usage on the user's own OpenRouter key.
- Top-up bonuses from [top-up-bonuses.md](top-up-bonuses.md), once they exist.

The markup is configurable (`WORKSPACED_VM_MARKUP_PERCENT`); generate the number into the page rather than writing
"35%" by hand.

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
