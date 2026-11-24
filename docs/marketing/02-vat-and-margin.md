# Stop paying VAT to Hetzner

Hetzner charges this account 20% VAT on every server (cx23 is €5.49 net, €6.59 billed), which means it has no VAT ID on
file. A VAT-registered business buying from Hetzner in Germany should be invoiced under reverse charge, without VAT.

That VAT is the largest cost lever there is: it is a sixth of every server's cost, and it decides whether the reward
programs below can pay out without losing money in the worst case (see [economics.md](economics.md)).

## Steps

1. Confirm the business is VAT-registered and the VAT ID is valid in VIES.
2. Add the VAT ID in the Hetzner console (account → billing data). Hetzner validates it and invoices without VAT from
   then on; ask Hetzner support whether past invoices can be corrected.
3. Check the next invoice shows reverse charge.

No code changes: the plan guard in `compute/VmPricing` reads Hetzner's `monthlyGross`, which then equals the net price,
so the guard's margins rise on their own.

## What it unlocks

| | Today (VAT paid) | With the VAT ID |
|---|---|---|
| Worst-case margin per paid euro | €0.116 | €0.248 |
| Referral program as written (20% credit) | loses €0.04 in the worst case | keeps about €0.12 |
| Partner commission of 15% | not allowed by the guard | allowed |

## Done

Prices are already labelled honestly: the picker, billing page and site all say they exclude VAT, which is added at
top-up.
