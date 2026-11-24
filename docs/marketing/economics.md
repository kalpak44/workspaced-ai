# Economics

How much each paid euro leaves after costs, and the rule that keeps every bonus, referral and commission profitable.

## Where the money comes from

- A workspace is sold as a plan at a fixed daily price before VAT (`workspaced.plans` in
  `apps/api/src/main/resources/application.yml`): Starter €0.35 (cx23), Builder €0.55 (cx33), Pro €0.95 (cx43).
- `compute/VmPricing` withholds a plan whose price, less a 7% Stripe fee, keeps under 10% over its server's cost with
  VAT × 1.155, the premium Hetzner's hourly billing adds before its monthly cap.
- A top-up is charged with 20% VAT on top (`workspaced.billing.vat-percent`); the balance receives the amount before VAT.
  The minimum top-up is €5 (`BillingService.MIN_TOP_UP_CENTS`).
- Workspaces are charged by the hour from the balance; a month is 730 hours.
- Hetzner charges this account VAT today; [02-vat-and-margin.md](02-vat-and-margin.md) is how to stop that.
- Admins pay cost price, so their usage carries no margin.
- The reactivation fee (€5) and balance that is never spent are pure margin; they are left out below to stay
  conservative.

## Costs every sign-up already carries

- One free demo workspace: 4 hours of the cheapest size, a few cents.
- One OpenRouter trial key, capped at $1 for its lifetime.

So a sign-up that never pays costs up to about €0.95. No program below rewards a sign-up on its own.

## Margin per paid euro spent

A plan's *cost share* is its server's cost over its price. It is highest on Pro and lowest on Builder.

| | Worst case | Typical | With Hetzner's VAT ID, worst case |
|---|---|---|---|
| Assumptions | €5 top-ups, VAT paid to Hetzner, VM gone within a month | €20 top-ups, VAT reclaimed, VM kept a month | €5 top-ups, VM gone within a month |
| Revenue (ex-VAT) | €1.000 | €1.000 | €1.000 |
| Hetzner cost (cost share) | −€0.791 (Pro: €22.86 / €28.90) | −€0.563 (Starter: €5.99 / €10.65) | −€0.659 (Pro: €19.05 / €28.90) |
| Stripe fee (~1.5% + €0.25 on the amount with VAT) | −€0.068 | −€0.031 | −€0.068 |
| **Margin** | **€0.141** | **€0.406** | **€0.273** |

Per plan in the worst case: Starter €0.152, Builder €0.187, Pro €0.141. The Stripe fee rate is an assumption; read the
real one from the Stripe dashboard and update this table.

## What a reward really costs

| Reward | Real cost per €1 given |
|---|---|
| Balance credit | the cost share: €0.79 worst case, €0.56 typical, because it is spent on plans |
| Cash payout | €1.00 plus payout fees |

## The guard

Every program declares its worst-case cost as a fraction of the paid euro it is earned on:

- credit programs: `percent / 100 × worstCostShare`
- cash programs: `percent / 100 + payout fee share`

At startup the API sums the programs that can apply to the same paid euro and refuses to start unless the worst-case
margin minus that sum stays at or above a floor (suggested 3%):

```
worstMargin = 1 − worstCostShare − worstStripeFee
worstMargin − Σ programCost ≥ floor
```

Implement it as a `@ConfigurationProperties` validator (`MarketingGuard` in a new `marketing` package) that reads
`PlanProperties`, `BillingProperties` and each program's properties. `worstCostShare` is the highest of the plans'
cost shares, read from the same Hetzner prices `VmPricing` checks, so it follows Hetzner's prices and the VAT ID. A config change that would sell at a loss then
fails the deployment instead of losing money silently. `worstStripeFee` is a property too (default `0.068`), so it can
follow Stripe's real pricing.

## Rules the guard assumes

- **One attribution per customer**: a friend referral or a partner, never both.
- **Only Stripe-paid money earns rewards**; credit never earns credit.
- **Refunds and disputes reverse rewards** through a negative ledger entry.
