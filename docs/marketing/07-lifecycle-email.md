# Lifecycle email

Short emails at the moments users usually drop off. Mail already goes through Mailjet via `MailService.notify`, and
users already have mail preferences (`MailPreferences`), so this is mostly content and scheduling.

## Shipped

`lifecycle/LifecycleMail` runs hourly and sends four tips, each once, through `NotificationService.sendOnce`: in the
app always, and by email unless the user turned off **Tips** in Settings → Email (`app_user.tips_mail`, on by
default). They go out like every other platform email, from `noreply@` in the shared mail layout. Admins get none.

| Tip | When |
|---|---|
| Your free trial is waiting | 1 day after sign-up, no trial started, never paid |
| What will you build next? | 1 day after the trial started, never paid |
| Invite a friend, and you both get extra | 7 days after the first paid top-up, while referrals are on |
| You still have X to use | 30 days after the last workspace charge, with €1 or more left |

Each is sent only within two days of its moment, so accounts that passed it before the job existed are not all
mailed at launch, and a short outage loses none. Every tip ends with how to turn tips off, and the privacy policy
lists them under legitimate interests.

## Still open

- "Put it on your own domain" for a workspace that has run a few days.
- Measuring what each tip leads to: a demo started, a workspace kept, a top-up or an invitation within 7 days.

## The original plan

## Onboarding

| When | Email |
|---|---|
| Sign-up | Welcome (exists) |
| Day 1, no demo started | "Start your free demo" with three recipes to try |
| Demo ended, not kept | "Your demo built X — pick up where you left off" (see [04-demo-conversion.md](04-demo-conversion.md)) |
| Day 3, workspace running | "Put it on your own domain" (Domain Connect) |
| Day 7, paying | "Invite a friend, both get credit" (see [13-referrals.md](13-referrals.md)) |

## Win-back

- **Paused for lack of funds:** a workspace is paused for 3 days before deletion
  (`workspaced.billing.paused-for`). Balance reminders already go out before that. Add one email on pause saying what
  starting it again costs; once [06-billing.md](06-billing.md) replaces the €5 fee with the paused days, that is a few
  cents rather than a penalty.
- **Inactive 30 days with a positive balance:** "You still have €X — here's what's new."

## Implementation

- **One scheduled job** (`LifecycleMail`, guarded by `ClusterLocks` like the other jobs) that finds users due for each
  step from facts already in the database: sign-up time, demo workspaces, ledger entries, workspace status.
- **One row per sent email** (`lifecycle_mail`: user, step, sent_at), so nothing is sent twice and the step can be
  measured.
- **Preference:** a "Tips and offers" toggle in General settings → Email, next to "Balance reminders". The onboarding
  and win-back emails send only when it is on, because offers need consent; account emails do not.
- Templates use the existing mail layout.

## Measure

Open rates matter less than what users do next: for each step, the share who started a demo, kept it, topped up or
invited someone within 7 days.
