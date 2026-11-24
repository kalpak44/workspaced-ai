# Partners

Creators — YouTubers, bloggers, course authors, recipe authors — earn real money when people they bring pay for
workspaces. It is a second tier on the attribution built for [13-referrals.md](13-referrals.md).

## How it differs from referrals

| | Referral (credit) | Partner (cash) |
|---|---|---|
| Real cost of €1 paid out | €0.56 to €0.79 | €1.00 plus payout fees |
| Who can join | Every user | Approved applicants |
| Legal side | Terms update | Identity checks, tax, invoices, ad disclosure |

## Commission

**10% of each referred customer's paid spend, for 12 months**, settled monthly. Commission is earned on paid balance
actually spent on VMs (`USAGE` ledger entries funded by Stripe-paid money), not on top-ups, so unspent or refunded money
never earns cash.

| | Worst case | Typical |
|---|---|---|
| Margin on €1 spent | €0.116 | €0.403 |
| Commission | −€0.100 | −€0.100 |
| Payout fees at a €50 minimum payout | ~−€0.005 | ~−€0.005 |
| **Platform keeps** | **+€0.011** | **+€0.30** |

Optionally 15% for partners bringing €1,000 a month. That loses about 4% in today's worst case, so it waits for
[02-vat-and-margin.md](02-vat-and-margin.md); the guard in [economics.md](economics.md) decides.

## Rules

- One attribution per customer: partner or friend, never both.
- **Minimum payout €50**; smaller amounts carry over, so fixed fees stay under 1%.
- **Hold period 30 days:** earnings are pending, then available. A dispute after that (card disputes can come up to about
  120 days later) is deducted from future earnings.
- No commission when a customer's card fingerprint matches the partner's own.
- Partners must disclose paid promotion and must not bid on our brand name in ads; commission can be withheld otherwise.

## Approval

A `partner` role in homelab-infra's `terraform/auth0/workspaced/roles.tf`, next to `admin`. Approving a partner is
assigning the role in Auth0, as with admins; the app keeps no user administration. Users apply through a form that
emails the admins.

## Payouts

**Stripe Connect Express.** Stripe handles identity checks, bank details and tax forms; the API stores only the connected
account id.

- Enable Connect in the Stripe dashboard (account settings have no API).
- Add Connect events (`account.updated`, `transfer.*`, `payout.*`) to `terraform/stripe/workspaced/webhooks/`.
- The existing restricted key needs Transfers and Accounts write access added; no new secret.
- VAT-registered partners need self-billed invoices; choose that setup in Connect.

Dedicated affiliate platforms (Rewardful, FirstPromoter, PartnerStack) would replace the payout and dashboard half of
this, at a monthly fee and with customer data sent to a third party.

## Attribution for video

- **Link:** `workspaced.online/r/<code>[/<campaign>]` goes to API `GET /api/v1/r/{code}`, which counts the click (a count
  per code, campaign and day; no IP), sets a 30-day `__Host-` attribution cookie on `ai.workspaced.online`, and
  redirects to the site. The last click wins.
- **Code at sign-up or first top-up**, for viewers who type it from a video description rather than click. Mobile
  viewers mostly do this.
- **Campaign tags** per video, so a partner sees which video converts.

## API

- **Migration:**
  - `partner` (user_id PK, code, rate_percent, stripe_account_id, status)
  - `partner_click` (code, campaign, day, count)
  - `partner_earning` (customer, usage reference, amount_micros, status pending/available/paid/reversed)
  - `partner_payout` (partner, amount_micros, stripe_transfer_id, created_at)
  - `referral` gains rows with `referrer_type = PARTNER`.
- **Settlement job** alongside the hourly charge: turns held earnings into available ones and, once a partner's
  available amount reaches €50, makes a Stripe Transfer and records the payout. Guarded by `ClusterLocks` like the other
  scheduled jobs.
- **Config:** `workspaced.partners` with `rate-percent`, `months`, `hold`, `minimum-payout`, `payout-fee-share`;
  validated by the guard.

## Pages

**Referrals section:** a "Have an audience? Become a partner" card with the application form.

**Partner section** in General settings, for the `partner` role only:

1. **Setup status:** connect payout account → verified → ready, with Stripe's onboarding link.
2. **Link builder:** campaign name → link and code to paste into a video description.
3. **Tiles:** clicks, sign-ups, paying customers, earned this month, pending, available, paid out.
4. **Funnel per campaign:** clicks → sign-ups → paying → €.
5. **Next payout:** progress toward €50 and the date.
6. **Payout history** with Stripe receipts.
7. **Rules:** rate, duration, hold, disclosure.

**Admin card:** partners with per-partner revenue, commission and net; rate override bounded by the guard; suspend; the
program's net.

## Order

1. Tracking and dashboard, with earnings calculated but **paid by hand** for the first few partners.
2. Stripe Connect automatic payouts once a few partners are earning.

## Legal

- `terms.html` gains a partner agreement.
- `privacy.html` covers the attribution cookie and click counting; the site's privacy notice may need to treat the
  cookie as non-essential.
- Commission is the partner's income to declare; confirm with an accountant whether any platform reporting applies.
