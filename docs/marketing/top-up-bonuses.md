# Top-up bonuses

Bonus credit on large top-ups and on turning automatic top-ups on. It pays for itself because Stripe's fixed fee makes
small top-ups expensive.

## Why it pays for itself

| Top-up | Stripe fee (~1.5% + €0.25 on the amount with VAT) | Share of the top-up |
|---|---|---|
| €5 | €0.34 | 6.8% |
| €20 | €0.61 | 3.0% |
| €100 | €2.05 | 2.0% |

A **5% bonus on €100+** costs about 3.7% (5% × 0.74, see [economics.md](economics.md)). A user who moves from €5 top-ups
to €100 saves us 4.8% in fees. We also get the money upfront, and unspent balance is pure margin.

## Recommendation

- **+5% credit on top-ups of €100 or more**, shown in `AddFundsDialog` next to the amount ("€100 → €105 balance").
- **+€2 once for turning on automatic top-up** with a saved card. Users on auto top-up rarely churn, and the card is
  already saved.

## Implementation

- **Config:** `workspaced.billing.bonus` with `threshold` (100.00), `percent` (5) and `auto-top-up-bonus` (2.00),
  checked by the startup guard in [economics.md](economics.md).
- **Ledger:** a new kind `TOP_UP_BONUS`, posted in the same transaction that credits the paid top-up, with the top-up id
  as the reference so a retried webhook credits it once.
- **Auto top-up bonus:** posted once per account, with the user id as the reference.
- **Refunds:** a refunded top-up reverses its bonus as well.
- **Billing page:** the history shows "Bonus for a €100 top-up".
