# Demo conversion

The free demo is the most expensive marketing we have — a VM plus up to $1 of trial AI usage per sign-up — and when it
ends, the user's work is deleted. Let them keep it by paying instead.

## Done

- **`POST /workspaces/{id}/keep`** turns the running demo into a paid workspace on the same VM: it requires the
  usual week of balance at its plan's price (`BillingService.requireCovered`), clears the demo flag and starts charging.
  A `402` carries `shortCents`, what the balance lacks.
- **No race with the reaper:** a keep is refused in a demo's last minute, and `DemoReaper` re-checks under the row lock
  that a workspace is still an expired demo before it stops the VM.
- **Web:** "Keep it" on the demo banner, "Keep this workspace" as the 30-minute warning's action (`?keep`) and as the
  tour's last screen, all opening one dialog. When the balance is short it adds exactly the missing amount (at least
  €5), remembers the keep across the trip to Stripe, and finishes it when the billing page sees the payment.

Still to do: the "your demo built X" email the day after a demo ended unkept.

## Recommendation

**"Keep this workspace" before the demo ends.** `DemoReaper` already warns 30 minutes before the end
(`workspaced.demo.warn-before`). At that point, and on the demo banner the whole time, offer one action: add funds and
keep the workspace as a paid one, on the same VM, with nothing lost.

People pay far more readily for work already done than for an empty machine.

## Implementation

- **API:** `POST /workspaces/{id}/keep` in `WorkspaceService`. It requires a week of the demo's plan
  (`BillingService.requireCovered`), clears the demo flag, and starts charging (`BillingService.startCharging`) at the
  plan's price. Without enough balance it answers `402` with the amount needed, which the web app turns into a
  top-up for exactly that amount.
- **Web:** a button on `DemoBanner` and in the warning notification. After Stripe Checkout returns, the app calls `keep`
  again automatically.
- **Same plan:** the demo runs on the cheapest plan in stock, normally Starter; keep it on that plan so no VM is
  recreated. Changing plan stays a separate action.
- **Mail:** if the demo ended without being kept, send one email the next day: "Your demo built X — start again with
  your work's recipe." Use `MailService.notify` and respect the user's mail preferences.

## Measure

Demos started versus demos kept, and versus first top-up within 7 days, on the funnel from [03-analytics.md](03-analytics.md).
