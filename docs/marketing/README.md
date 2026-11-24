# Marketing

Ideas for growing workspaced, each with how it would be built here. Nothing in this folder is implemented yet; each
file is a plan. Every idea that gives away money or credit is bounded by [economics.md](economics.md), which shows how
much each paid euro leaves after costs and the startup check that keeps every program inside that.

## Ideas

| File | What | Effort | Cost to run |
|---|---|---|---|
| [billing.md](billing.md) | Billing that feels fair: VAT shown once, daily prices, smaller first top-up, hibernate | Small to large | Neutral or positive |
| [analytics.md](analytics.md) | Measure the funnel from visit to paying user | Small | None (self-hosted) |
| [tour.md](tour.md) | Record where users stop in the guided tour, bring them back, end it on a next step | Small | None |
| [demo-conversion.md](demo-conversion.md) | Turn the free demo into a paid workspace without losing work | Medium | Lowers cost per user |
| [top-up-bonuses.md](top-up-bonuses.md) | Bonus credit for large top-ups and automatic top-ups | Small | Pays for itself |
| [referrals.md](referrals.md) | Users invite friends and earn balance credit | Medium | 20% credit, ~15% real cost |
| [partners.md](partners.md) | Creators earn cash commission on customers they bring | Large | 10% cash of paid spend |
| [recipe-pages.md](recipe-pages.md) | A search landing page per public recipe | Medium | None |
| [open-in-button.md](open-in-button.md) | "Open in workspaced" button for GitHub READMEs | Medium | Demo cost per click-through |
| [listings-and-launches.md](listings-and-launches.md) | OpenRouter rankings, directories, Show HN, Product Hunt | Small | Time only |
| [comparison-pages.md](comparison-pages.md) | Transparent pricing and comparisons with alternatives | Small | None |
| [lifecycle-email.md](lifecycle-email.md) | Onboarding and win-back email | Small | Mailjet volume |
| [showcase.md](showcase.md) | Opt-in gallery of what users built, social proof | Medium | None |

## Order

1. **Analytics** and the **tour** step history first: without them nothing below can be measured or tuned.
2. **Billing** label and top-up fixes, **demo conversion** and **top-up bonuses**: quick, and all raise revenue from
   users already signing up.
3. **Referrals**, then **partners**, which reuses the same attribution.
4. **Recipe pages** and the **README button** for long-term, compounding traffic.
5. The rest whenever there is time; listings and launches are best done once demo conversion works, so the spike of
   visitors is not wasted.

## Rules that apply to every idea

- **Credit, not cash, unless the receiver is a vetted partner.** Credit is spent at marked-up prices, so a euro of it
  costs about €0.74; a euro of cash costs a euro plus fees.
- **Only Stripe-paid money earns rewards.** Bonus credit, referral credit and demo time never generate further rewards.
- **Admins are excluded from every program.** They pay cost price, so they bring no margin to share.
- **Product behaviour changes go into `apps/site/src/terms.html` and `privacy.html`**, since those pages must describe
  how the product actually behaves.
