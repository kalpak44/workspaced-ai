# Marketing

Ideas for growing workspaced, each with how it would be built here, numbered in the order to do them. Every idea that
gives away money or credit is bounded by [economics.md](economics.md), which shows how much each paid euro leaves after
costs and the guard that keeps every program inside that.

## Done

| What | Where | Commit |
|---|---|---|
| Prices say they exclude VAT, daily price first, €10 first top-up, a week of balance to create | [06-billing.md](06-billing.md) | `220271f` |
| Fixed-price plans (Starter, Builder, Pro) with a margin guard, replacing the markup | [06-billing.md](06-billing.md) | `0d46950` |
| Pricing page at `/pricing` | [09-comparison-pages.md](09-comparison-pages.md) | `0d46950` |
| Plans fall back to another Hetzner location, then to Starter Flex, when sold out | [01-plan-availability.md](01-plan-availability.md) | `90df731`, `c0f5398` |
| Model provider step defaults to Connect OpenRouter, with a two-line AI notice | [04-demo-conversion.md](04-demo-conversion.md) | `f43af16` |
| OpenRouter attribution headers on every agent request | [10-listings-and-launches.md](10-listings-and-launches.md) | `f4eed5a` |
| Sign-up funnel per source for admins (General settings → Funnel), with the tour's step history | [03-analytics.md](03-analytics.md), [05-tour.md](05-tour.md) | `ded8fb1` |
| Keep the demo as a paid workspace on the same VM, from the banner, the warning and the tour's end | [04-demo-conversion.md](04-demo-conversion.md) | `27462c2`, `232557c` |
| Tour rewritten for non-technical users: shorter, chapters, ideas to tap, a celebration, ends on keeping | [05-tour.md](05-tour.md) | `232557c` |
| Admins are told when no plan has been available for 15 minutes, and when one is back | [01-plan-availability.md](01-plan-availability.md) | `bbcf259` |
| Referrals: 10% extra for the friend, 10% for the referrer, guarded by the plan margin check | [13-referrals.md](13-referrals.md) | this change |
| VAT ID BG206070477 (Ispolink EOOD) on the Hetzner account: servers billed under reverse charge, 0% VAT | [02-vat-and-margin.md](02-vat-and-margin.md) | none, console only |

## Next, in order

| # | File | What | Why here | Effort |
|---|---|---|---|---|
| 1 | [01-plan-availability.md](01-plan-availability.md) | "Tell me when it's back" on a sold-out plan | Nothing below matters if no workspace can be created | Small |
| 2 | [02-vat-and-margin.md](02-vat-and-margin.md) | Settle OSS and reverse charge for business customers with an accountant | Decides how `BillingService` computes VAT as sales grow | None |
| 3 | [03-analytics.md](03-analytics.md) | Site page views, only if the funnel leaves a question it cannot answer | The funnel already counts every step after the visit | Small |
| 5 | [05-tour.md](05-tour.md) | Bring people back to a paused tour; cut more steps once the history shows where people stop | The tour is every user's first five minutes | Small |
| 6 | [06-billing.md](06-billing.md) | Paused days instead of the €5 fee, spending this month, then hibernate | Cheap trust fixes; hibernate is the large one and comes last | Small, then large |
| 7 | [07-lifecycle-email.md](07-lifecycle-email.md) | Onboarding and win-back email | Brings back the users 4 to 6 lose, at Mailjet volume only | Small |
| 8 | [08-top-up-bonuses.md](08-top-up-bonuses.md) | Bonus credit on large and automatic top-ups | Pays for itself in Stripe fees once users are paying | Small |
| 9 | [09-comparison-pages.md](09-comparison-pages.md) | Honest comparisons with Codespaces, Replit and a VPS | Search traffic from people already choosing a tool | Small |
| 10 | [10-listings-and-launches.md](10-listings-and-launches.md) | Directories, Show HN, Product Hunt | A one-off spike, so only after 4 works and 3 can attribute it | Time only |
| 11 | [11-recipe-pages.md](11-recipe-pages.md) | A search landing page per public recipe | Compounding traffic, slow to pay off | Medium |
| 12 | [12-open-in-button.md](12-open-in-button.md) | "Open in workspaced" for GitHub READMEs | Compounding traffic; costs a demo per click-through | Medium |
| 13 | [13-referrals.md](13-referrals.md) | Reverse referral rewards on refunds and disputes, then the smaller extras | The program is live; this closes the refund gap | Small |
| 14 | [14-partners.md](14-partners.md) | Creators earn cash commission | Reuses 13's attribution; worth it only with traffic to share | Large |
| 15 | [15-showcase.md](15-showcase.md) | Opt-in gallery of what users built | Needs users with published work first | Medium |
| 16 | [16-recipe-authors.md](16-recipe-authors.md) | Recipe authors earn credit on paid usage of their recipes | Uses are already recorded; the reward needs 13's ledger work | Medium |
| 17 | [17-workspace-transfer.md](17-workspace-transfer.md) | Hand a workspace to another account; the builder becomes the client's referrer | Turns freelancers into a sales channel; attribution needs 13 or 14 | Large |

A file's number is its place in this list. When the order changes, rename the files so it stays true; when an item
ships, move it to **Done** above and leave the remaining work in its file.

## Rules that apply to every idea

- **Credit, not cash, unless the receiver is a vetted partner.** Credit is spent on plans, so a euro of it costs the
  plan's cost share, €0.56 to €0.79; a euro of cash costs a euro plus fees.
- **Only Stripe-paid money earns rewards.** Bonus credit, referral credit and demo time never generate further rewards.
- **Admins are excluded from every program.** They pay cost price, so they bring no margin to share.
- **Product behaviour changes go into `apps/site/src/terms.html` and `privacy.html`**, since those pages must describe
  how the product actually behaves, and price changes into `pricing.html`.
