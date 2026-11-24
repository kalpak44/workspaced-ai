# Analytics

Measure where people drop off between a site visit and a user who keeps paying. Every other idea in this folder is a
guess until this exists.

## The funnel

1. Visit to `workspaced.online` (by source: search, referral, partner campaign, launch post)
2. Sign-up
3. Demo started
4. First paid top-up
5. Still paying after 30 days

## Done

- **Source on sign-up** (migration `V66`): `utm_source` and `utm_campaign` are saved on the account when it is made.
  The site's `attribution.js` adds them to every link into the app and to its own pages, falling back to the
  referring host when a visit has no UTM tags; the app forwards them from its own URL too. The API keeps them in the
  encrypted sign-in cookie through Auth0's round trip. `privacy.html` says so.
- **Admin funnel:** `GET /admin/funnel?from=&to=` and General settings → Funnel. For the accounts made in a period
  (admins left out), it counts sign-ups, demos started, paid top-ups and accounts still charged 30 days after signing
  up, in total and per source and campaign, with the tour's step history beside them.

Page views (step 1) are not counted. Steps 2 to 5 do not need them, so Umami waits until a question needs them.

## Recommendation

**Self-host Umami or Plausible on the k3s cluster** for site and app page views. Both work without cookies, so the
privacy notice on the site needs no consent change, and no visitor data leaves our infrastructure. Add the script tag to
every page in `apps/site/src` (each page carries its own header, so it is a change to every page) and to
`apps/web/index.html`.

**Count steps 2 to 5 in the API, not in the browser.** They are already database facts: `app_user` creation, demo
workspaces, `TOP_UP` ledger entries, and usage after 30 days. An ad blocker cannot hide them, and they can be joined to
the attribution stored by [13-referrals.md](13-referrals.md) and [14-partners.md](14-partners.md).

## Implementation

- **Source on sign-up:** store `utm_source`, `utm_campaign` and the referral or partner code on the user at first
  sign-in, carried through the Auth0 round trip in the same `__Host-` encrypted cookie as the sign-in request.
- **Admin funnel:** `GET /admin/funnel?from=&to=` returning counts per step and per source; a chart on an admin-only
  page built from `MetricTile` and `MetricChart`.
- **Hosting:** Umami needs Postgres; reuse the cluster's database operator or a small dedicated instance under
  `gitops/clusters/homelab/apps/private/umami/` in homelab-infra, reachable at `umami.internal`, with only its tracking
  endpoint exposed publicly through the tunnel.

## Done when

The admin page answers "what share of last month's sign-ups paid, and from which source did the payers come".
