# Economics

How much each paid euro leaves after costs, and the rule that keeps every bonus, referral and commission profitable.

## Where the money comes from

- A workspace is sold as a plan at a fixed daily price (`workspaced.plans` in
  `apps/api/src/main/resources/application.yml`): Starter €0.35, Builder €0.55, Pro €0.95. `compute/VmPricing` withholds
  a plan whose price, less a 7% Stripe fee, keeps under 10% over its server's cost with VAT × 1.155 for hourly billing.
  The margin tables below still assume the old 35% markup.
- A top-up is charged with 20% VAT on top (`workspaced.billing.vat-percent`); the balance receives the amount before VAT.
  The minimum top-up is €5 (`BillingService.MIN_TOP_UP_CENTS`).
- Workspaces are charged by the hour from the balance; a month is 730 hours.
- The price is based on Hetzner's price *with* VAT, and the top-up adds VAT again; [billing.md](billing.md) explains
  why a VAT-registered business should base it on the net price instead.
- Admins pay cost price, so their usage carries no margin.
- The reactivation fee (€5) and balance that is never spent are pure margin; they are left out below to stay
  conservative.

## Costs every sign-up already carries

- One free demo workspace: 4 hours of the cheapest size, a few cents.
- One OpenRouter trial key, capped at $1 for its lifetime.

So a sign-up that never pays costs up to about €0.95. No program below rewards a sign-up on its own.

## Margin per paid euro spent

| | Worst case | Typical |
|---|---|---|
| Assumptions | €5 top-ups, Hetzner VAT not reclaimed | €20 top-ups, Hetzner VAT reclaimed |
| Revenue (ex-VAT) | €1.000 | €1.000 |
| Hetzner cost | −€0.741 (1/1.35) | −€0.623 (1/1.35/1.19) |
| Stripe fee (~1.5% + €0.25 on the amount with VAT) | −€0.068 | −€0.031 |
| **Margin** | **€0.191** | **€0.346** |

The Stripe fee rate is an assumption; read the real one from the Stripe dashboard and update this table.

## What a reward really costs

| Reward | Real cost per €1 given |
|---|---|
| Balance credit | €0.74 (€0.62 with VAT reclaimed), because it is spent at marked-up prices |
| Cash payout | €1.00 plus payout fees |

## The guard

Every program declares its worst-case cost as a fraction of the paid euro it is earned on:

- credit programs: `percent / 100 × 1 / (1 + markup)`
- cash programs: `percent / 100 + payout fee share`

At startup the API sums the programs that can apply to the same paid euro and refuses to start unless the worst-case
margin minus that sum stays at or above a floor (suggested 3%):

```
worstMargin = 1 − 1/(1 + markup) − worstStripeFee
worstMargin − Σ programCost ≥ floor
```

Implement it as a `@ConfigurationProperties` validator (`MarketingGuard` in a new `marketing` package) that reads
`VmPricingProperties`, `BillingProperties` and each program's properties. A config change that would sell at a loss then
fails the deployment instead of losing money silently. `worstStripeFee` is a property too (default `0.068`), so it can
follow Stripe's real pricing.

## Rules the guard assumes

- **One attribution per customer**: a friend referral or a partner, never both.
- **Only Stripe-paid money earns rewards**; credit never earns credit.
- **Refunds and disputes reverse rewards** through a negative ledger entry.
