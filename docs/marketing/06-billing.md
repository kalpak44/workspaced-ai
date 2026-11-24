# Billing that feels fair

Billing is where users decide whether the product is expensive. Each change here removes a moment where a user feels
charged more than expected, without giving up margin (see [economics.md](economics.md)).

## Done

- **VAT shown once** (`220271f`): the picker, billing page and site say prices exclude VAT, which is added at top-up.
- **Daily price first** (`220271f`): "€0.35 a day", then the month, then the hour, in the picker, the billing page's
  spending table and the site.
- **A week of balance, not a month** (`220271f`): `workspaced.billing.require-covered`, 7 days by default, also in
  `terms.html`.
- **A first top-up that matches the first workspace** (`220271f`): €10, €25 and €50, €10 preselected, with what it buys
  on the default plan. €5 is left out, as Stripe's fixed fee takes about 7% of it.
- **Fixed-price plans** (`0d46950`): Starter, Builder and Pro at €0.35, €0.55 and €0.95 a day, replacing Hetzner's price
  plus a markup, with a guard that withholds a plan whose price stops covering its server. See
  [01-plan-availability.md](01-plan-availability.md) for keeping them in stock.
- **A pricing page** (`0d46950`): `/pricing` on the site, linked from every page.
- **"Your AI usage is billed by OpenRouter, not us"** is on the pricing page.

## Next: replace the reactivation fee with the cost of keeping it

A workspace paused because the balance ran out costs a flat €5 to start again. Hetzner still bills a stopped server,
but only at the plan's own rate, so a flat fee for running out of funds feels like a penalty, at the exact moment the
user is deciding whether to come back.

**Charge the paused days at the plan's daily price instead.** Starting a Starter workspace again after one day costs
€0.35 instead of €5, and after the full 3 days €1.05. The platform still covers what the pause cost, and the billing page
can say so plainly: "Kept for you for 2 days: €0.70".

- **API:** `UsageCharger` charges the hours a workspace spent paused when it resumes, instead of
  `workspaced.billing.reactivation-fee`.
- **Copy:** `Notifications.java` and `FundsReminder` mention the fee, and so does the billing page's funds warning.
- **Terms and site:** `terms.html` section 11, and the pricing and support pages' answers on running out.

This also replaces the fee waiver in [07-lifecycle-email.md](07-lifecycle-email.md).

## Next: small things that build trust

- **Spending this month** on the billing page, with what is left of the balance, so nothing comes as a surprise.
- **A monthly spending limit:** an optional cap after which new workspaces cannot be created and auto top-up stops,
  for users afraid of a runaway bill. Balance is prepaid, so it mostly reassures.
- **Unused balance refundable on request.** It reverses the "No refunds" clause in `terms.html` section 10, so it is a
  legal change as much as a product one. It costs only balance that would otherwise go unspent, and makes larger top-ups
  feel safe.

## Later: let users stop paying when they are not using it

A workspace costs the same whether anyone uses it or not, because a powered-off Hetzner server is still billed.
Occasional users therefore pay for a whole month to use it a few evenings.

**Hibernate.** A user hibernates a workspace; the VM manager snapshots the server and deletes it, so only the snapshot is
billed (a few cents per GB a month). Waking it creates a server from the snapshot.

- **Price:** the snapshot's storage cost with a margin, shown as "Hibernated: €0.40 a month".
- **VM manager:** `hibernate` and `wake` next to the existing power operations in `VmService`; the VM keeps its id and
  its firewall is reattached.
- **Keep the addresses:** a domain connected through Domain Connect's `workspace-apex` template points A/AAAA records at
  the VM's IP, not at its name, so a new IP breaks it. Keep the Primary IPs unassigned while hibernated (about €0.50 a
  month, part of the hibernated price) and reattach them on wake.
- **Wake time:** a snapshot of a large disk takes minutes to restore, and the supervisor re-enrolls with the agent hub.
- **Auto-hibernate (optional):** after N days with no session, notify, then hibernate.

This is the largest change here and the one that makes "pay only for what you use" true.
