# Billing that feels fair

Billing is where users decide whether the product is expensive. Today the prices are low, but several details make
them feel higher than they are: VAT appears twice, the first top-up suggested is €25, a workspace needs a month of
balance before it can be created, and running out of funds costs a €5 fee. Each change below keeps the platform's
margin (see [economics.md](economics.md)) and removes a moment where a user feels charged more than expected.

## 1. Show VAT once

**Today:** the size picker shows Hetzner's price *with VAT* × 1.35 and says nothing about VAT
(`molecules/VmSizePicker.tsx`). The top-up then adds 20% VAT on top (`AddFundsDialog`). A size listed at €6.41 a month
takes €7.69 of card charges to fund, and the user meets that difference only at checkout.

**Also check:** the business charges VAT, so it is VAT-registered, and Hetzner should then invoice it under reverse
charge, without VAT. If the Hetzner invoices confirm that, the "VAT" inside our price is not a cost at all — it is an
extra ~19% markup that is labelled as tax.

**Recommendation:** price on Hetzner's net cost and say "excl. VAT" everywhere a price is shown.

- `VmPricing` uses `monthlyNet` instead of `monthlyGross`, and the markup is raised so revenue stays where it is:
  1.35 × Hetzner's VAT rate ≈ **60% on net** gives the same money with honest labels. A lower markup such as 50% makes
  prices visibly cheaper and still leaves about 26% margin in the worst case.
- The picker, billing page and site say "€6.38 / month excl. VAT" — about the number shown today, now honestly
  labelled, and the convention developers know from Hetzner, AWS and every other cloud.
- `AddFundsDialog` keeps showing amount + VAT = total, which now matches what the picker promised.

Only do this after checking the Hetzner invoices; if Hetzner does charge VAT that cannot be reclaimed, keep the gross
base and only fix the labels.

## 2. Show the daily price first

A month of a small workspace sounds like a subscription; a day sounds like a coffee. The picker currently leads with
the monthly price and shows an hourly one with three decimals (`€0.009/hour`), which reads as tiny but meaningless.

**Recommendation:** lead with **"€0.21 a day"**, then the monthly price, then the hourly rate small. Use the same order
on the billing page's spending table and on the site's pricing page from [comparison-pages.md](comparison-pages.md).

## 3. Require a week of balance, not a month

**Today:** `BillingService.requireMonthOf` refuses to create a workspace unless the balance covers a month of every
workspace plus the new one. Someone who wants to try one more size has to top up a full extra month first.

**Recommendation:** require **7 days** of the new total (`workspaced.billing.require-covered`, default `P7D`), and show
how long the balance lasts after creating, as `CreateWorkspaceDialog` already does with `coverage()`.

The balance is prepaid and charged hourly, so the platform carries no credit risk either way: at worst a workspace is
paused a few weeks earlier. Balance reminders and automatic top-up already cover that.

## 4. A first top-up that matches the first workspace

**Today:** `AddFundsDialog`'s presets are €25, €50, €100 and €250, with €25 preselected, while the API accepts €5.

**Recommendation:**

- For a first top-up, offer **€10, €25, €50**, preselect €10, and say what it buys: "€10 ≈ 7 weeks of a small
  workspace".
- After the first top-up, switch to the current presets, with €100 marked "+5% bonus" once
  [top-up-bonuses.md](top-up-bonuses.md) exists.
- Avoid €5 as a preset: Stripe's fixed fee takes about 7% of it.

## 5. Replace the reactivation fee with the cost of keeping it

**Today:** a workspace paused because the balance ran out costs a flat €5 to start again. The VM is powered off, but
Hetzner still bills a stopped server, so keeping it does cost something — about €0.20 a day for a small one, far less
than €5. A flat fee for running out of funds feels like a penalty, at the exact moment the user is deciding whether to
come back.

**Recommendation:** charge **the paused days at the normal daily price** instead of a fee. Starting again after one day
costs about €0.20 instead of €5; after the full 3 days, about €0.60. The platform still covers what the pause cost, and
the billing page can say so plainly: "Kept for you for 2 days: €0.42".

If a fee is wanted as an incentive to keep a balance, waive it the first time per account.

## 6. Let users stop paying when they are not using it

The pitch is "charged by the hour", but a workspace costs the same whether anyone uses it or not, because a powered-off
Hetzner server is still billed. Occasional users therefore pay for a whole month to use it a few evenings.

**Recommendation: hibernate.** A user hibernates a workspace; the VM manager snapshots the server and deletes it, so
only the snapshot is billed (a few cents per GB a month). Waking it creates a server from the snapshot.

- **Price:** the snapshot's storage cost with the same markup, shown as "Hibernated: €0.40 a month".
- **VM manager:** `hibernate` and `wake` operations next to the existing power operations in `VmService`; the VM keeps
  its id, its DNS name moves to the new server's address, and its firewall is reattached.
- **Auto-hibernate (optional):** after N days with no session, notify, then hibernate.
- **Margin:** hibernated hours carry the same markup, so it is never a loss; it trades some revenue from idle
  workspaces for users who would otherwise delete their workspace or never pay at all.

This is the largest change here and the one that makes "pay only for what you use" true.

## 7. Small things that build trust

- **Spending this month** on the billing page, with what is left of the balance, so nothing comes as a surprise.
- **A monthly spending limit:** an optional cap after which new workspaces cannot be created and auto top-up stops,
  for users afraid of a runaway bill. Balance is prepaid, so it mostly reassures.
- **Unused balance refundable on request**, stated in `terms.html`. It costs only the balance that would otherwise go
  unspent, and makes larger top-ups feel safe.
- **"Your AI usage is billed by OpenRouter, not us"** near the price, so users do not suspect hidden AI charges.

## Order

1. Labels (1), the daily price (2) and the first top-up (4): small web changes, no effect on margin.
2. A week of balance (3) and paused days instead of a fee (5): small API changes.
3. Net pricing (1), after checking the Hetzner invoices.
4. Hibernate (6): VM manager work, the biggest change in how the product feels.

## Site and terms

`apps/site/src/terms.html` describes the reactivation fee, pausing and pricing, so items 1, 3, 5 and 6 each need a
matching change there, as do the site's pricing statements.
