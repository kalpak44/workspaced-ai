# Listings and launches

Free places to be listed, and one-off launches. Do the launches after [demo-conversion.md](demo-conversion.md) works, so
a spike of visitors is not wasted on a demo that ends and deletes their work.

## OpenRouter app rankings

OpenRouter lists apps by token usage, attributed by the `HTTP-Referer` and `X-Title` headers on requests. Set both on
traffic that goes through trial keys (`https://workspaced.online`, `workspaced`), in the harness's OpenRouter provider
config. Check whether requests made with users' own keys can carry the headers too; that is most of the traffic.

## Directories

- MCP server directories, for the MCP servers recipes ship
- awesome-lists: awesome-selfhosted (if anything is open source), AI agents, developer tools
- AI tool directories (There's An AI For That, Futurepedia and similar)
- alternative-to listings (AlternativeTo, SaaSHub) next to Codespaces, Replit and cloud coding agents

Keep one short description, one logo and one screenshot in `docs/marketing/assets/` so every listing is consistent.

## Launches

- **Show HN:** lead with how it works — per-user Hetzner VMs, rootless Docker for the agent, priced at Hetzner plus a
  stated markup. Hacker News rewards technical substance and penalises marketing language.
- **Product Hunt:** prepare a short video, a launch-day promo code (reuse the referral code machinery, bounded by the
  guard in [economics.md](economics.md)), and be present all day to answer comments.
- **Indie Hackers:** a build-in-public post with real numbers.

## Communities

r/selfhosted, r/LocalLLaMA, and developer Discords. Post genuinely useful write-ups ("how we run AI agents on
per-user VMs"), not ads; mention the product once, at the end.

## Measure

Tag every link with `utm_source` and `utm_campaign` so [analytics.md](analytics.md) shows which launch brought payers,
not only visitors.
