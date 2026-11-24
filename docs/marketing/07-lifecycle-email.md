# Lifecycle email

Short emails at the moments users usually drop off. Mail already goes through Mailjet via `MailService.notify`, and
users already have mail preferences (`MailPreferences`), so this is mostly content and scheduling.

## Onboarding

| When | Email |
|---|---|
| Sign-up | Welcome (exists) |
| Day 1, no demo started | "Start your free demo" with three recipes to try |
| Demo ended, not kept | "Your demo built X — pick up where you left off" (see [demo-conversion.md](demo-conversion.md)) |
| Day 3, workspace running | "Put it on your own domain" (Domain Connect) |
| Day 7, paying | "Invite a friend, both get credit" (see [referrals.md](referrals.md)) |

## Win-back

- **Paused for lack of funds:** a workspace is paused for 3 days before deletion
  (`workspaced.billing.paused-for`). Balance reminders already go out before that. Add one email on pause offering to
  **waive the €5 reactivation fee once** if they top up within the 3 days. Losing the user costs more than the fee.
- **Inactive 30 days with a positive balance:** "You still have €X — here's what's new."

## Implementation

- **One scheduled job** (`LifecycleMail`, guarded by `ClusterLocks` like the other jobs) that finds users due for each
  step from facts already in the database: sign-up time, demo workspaces, ledger entries, workspace status.
- **One row per sent email** (`lifecycle_mail`: user, step, sent_at), so nothing is sent twice and the step can be
  measured.
- **Preference:** a "Tips and offers" toggle in General settings → Email, next to "Balance reminders". The onboarding
  and win-back emails send only when it is on, because offers need consent; account emails do not.
- **Waiver:** a `REACTIVATION_WAIVER` ledger entry, once per account.
- Templates use the existing mail layout.

## Measure

Open rates matter less than what users do next: for each step, the share who started a demo, kept it, topped up or
invited someone within 7 days.
