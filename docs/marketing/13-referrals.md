# Referrals

Every user can invite friends. Both earn balance credit, paid only from money the friend actually pays.

## Shipped

- **Rewards**, configured in `workspaced.referrals`: the friend gets 10% extra on paid top-ups for 90 days after
  sign-up, up to €10; the referrer gets 10% of them for 365 days, up to €50 per friend. Credit only, in whole cents.
- **Profit guard:** `VmPricing` counts both shares as extra server cost (`ReferralProperties.rewardShare`), so a
  plan that could lose money with referral credit spent on it is withheld, from Hetzner's live prices. This holds
  even for someone who invites themselves, which is why there is no card fingerprint check yet.
- **Attribution:** `workspaced.online/?ref=CODE`; the site's `attribution.js` and the app's sign-in carry `ref`
  through the encrypted sign-in cookie, and `ReferralBinding` binds it once when the account is made. A referral
  link with no `utm_source` records `referral` as the sign-up source, so the admin funnel shows it.
- **Crediting:** `BillingService.credit` asks every `TopUpRewards` in the transaction that credits a paid top-up,
  ledger kinds `REFERRAL_BONUS` and `REFERRAL_REWARD` with the top-up id as reference. Admins take part on neither
  side.
- **Page:** General settings → Referrals: link with copy and share, tiles, a funnel (signed up, paid, still
  earning), friends numbered rather than named, and the fine print. Terms section 10 and the privacy policy describe
  it.

- **Refunds and disputes:** `charge.refunded` and `charge.dispute.created` take back what the top-up credited, its
  own balance and every reward posted with it, in proportion to the amount refunded; each later refund of the same
  charge takes only the difference, as `REVERSAL` ledger entries. A balance may go negative. Both events are in
  `terraform/stripe/workspaced/webhooks/`.

## Still open

- A dispute decided in our favour does not give the balance back; do it by hand until it happens often.
- Card fingerprint check, a monthly cap on rewarded friends, an email toggle for reward notifications, an admin card
  with the program's cost, and an "Invited by a friend?" banner on the site.

## The original plan

## Rewards

- **The friend:** +10% extra balance on each paid top-up during their first 3 months, capped at €10 in total.
- **The user who invited them:** 10% of every top-up the friend pays, for 12 months, capped at €50 per friend.

Nothing is paid for a sign-up, the demo or the trial key, so a fake account that never pays earns nothing.

## What the platform keeps per €1 the friend spends

| Period | Paid out | Real cost | Worst case | Typical |
|---|---|---|---|---|
| Both rewards running | 20% | €0.113 to €0.158 | −€0.042 | +€0.29 |
| Friend's bonus used up | 10% | €0.056 to €0.079 | +€0.037 | +€0.35 |
| After 12 months | 0 | 0 | +€0.116 | +€0.40 |

**The worst case loses money while Hetzner charges this account VAT.** Do
[02-vat-and-margin.md](02-vat-and-margin.md) first (worst case +€0.12 with both rewards running), or lower both rewards
to 5%.

See [economics.md](economics.md) for the assumptions and the startup guard.

## Rules

- Only Stripe-paid top-ups count; credit never earns credit.
- Admins are excluded on both sides.
- One referrer per account, set only at first sign-up.
- No reward when the friend's card fingerprint (Stripe `payment_method.card.fingerprint`) matches the referrer's.
- Optional cap of 20 rewarded friends per referrer per month.
- Refunds and disputes reverse the rewards through a negative ledger entry; a balance may go negative.
- Credit cannot be withdrawn or transferred.

## API

- **Migration:**
  - `referral_code` (user_id PK, unique code)
  - `referral` (referee_user_id PK, referrer_user_id, referrer_type `USER` or `PARTNER`, campaign, signed_up_at,
    first_paid_at, referrer_until, referee_until, earned_micros, bonus_micros)

  `referrer_type` and `campaign` are there from the start so [14-partners.md](14-partners.md) reuses the table.
- **Ledger kinds:** `REFERRAL_REWARD`, `REFERRAL_BONUS`, `REFERRAL_REVERSAL`. `Ledger.post` with the top-up id as the
  reference already makes a retried webhook pay once.
- **`ReferralService.onPaidTopUp(user, topUp)`**, called where `BillingService` credits a paid top-up, in the same
  transaction.
- **Refunds:** handle `charge.refunded` and `charge.dispute.created` in `BillingService.handleEvent`, and add both events
  to the webhook endpoint in homelab-infra's `terraform/stripe/workspaced/webhooks/`.
- **Attribution:** a `workspaced.online/?ref=CODE` link reaches the app, the code travels through sign-in in the
  encrypted `__Host-` cookie that already carries the sign-in request, and it is bound when `app_user` is first created.
  Only user ids are stored.
- **`GET /account/referrals`:** code, link, totals and a list of friends, each shown by an initial and a status
  (Signed up, First top-up, Earning until …, Finished). Names stay in Auth0.
- **Config:** `workspaced.referrals` with `enabled`, `referrer-percent`, `referee-percent`, `referee-cap`,
  `referrer-cap`, `referee-months`, `referrer-months`; validated by the guard.
- **Notification:** a `REFERRAL_REWARD` notification and an optional email, with a toggle beside "Balance reminders"
  (`MailPreferences`).

## Page: General settings → Referrals

A fourth `SectionNav` entry in `apps/web/src/components/pages/GeneralSettingsPage.tsx` (lucide `Gift`), reachable by URL
like `mail`, built from the existing `card`, `CardHeader`, `SettingRow` and `MetricTile`.

1. **Invite card:** "Give your friends 10% extra, earn 10% of what they spend." Link, copy button, the browser's share
   sheet (`navigator.share`) and email.
2. **Tiles:** Friends invited, Paying friends, Earned so far, Earning this month.
3. **Earnings as compute time:** "€12.40 earned ≈ 58 hours of a 2 vCPU / 4 GB workspace", computed from `/vm-sizes`.
4. **Slider:** number of friends × monthly spend → "5 friends at €20/mo → €10/mo, about 1.5 workspaces free".
5. **Friends list:** initial, status, and progress toward the €50 cap.
6. **How it works:** three steps and the fine print.
7. **Admin-only card:** total credit given, its real cost, margin from referred users, net.

`BillingPage`'s history shows the new entries as "Referral reward" and "Welcome bonus".

## Outside the API and web app

- `apps/site/src/terms.html`: program rules, credit only.
- `apps/site/src/privacy.html`: we store who referred whom.
- `apps/site/src/index.html`: an "Invited by a friend? +10% on your top-ups" banner when `?ref` is present.

## Open questions

- How bonus credit is treated for VAT; confirm with an accountant.
