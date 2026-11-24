# Guided tour

The tour is the first thing a new user does: it creates the free demo, applies the "Simple web page" recipe and changes
the page through chat (`apps/web/src/components/organisms/DemoTour.tsx`, steps in `apps/web/src/lib/tour.ts`). It is
where a sign-up either sees the value or leaves.

## Already in place

The current step is saved on the account, not in the browser: `app_user.tour_step` and the ids it needs
(`V61__tour_progress.sql`, `TourProgress`, saved by `AuthService.saveTour` on every step). A refresh, another browser or
another device resumes at the same step, and skipping pauses it in place rather than losing it.

What it cannot tell is **where people stop**: it keeps only the current step, and finishing or ending the tour sets it
to null, so a user who finished looks the same as one who never started.

## 1. Record each step, not only the current one — done

`tour_event` (migration `V66`) keeps every move: `REACHED`, `PAUSED`, `RESUMED` and `ENDED`, with the step. The API
works out the event from the stored bookmark before and after each `saveTour`, so the web app sends nothing extra.
Finishing and quitting both end the tour, so ending on the last step is what counts as finishing. The admin funnel
shows how many reached each step and how many skipped, quit or are still at it. Step durations are not shown yet.

**Recommendation:** a `tour_event` table — user, step, event (`reached`, `paused`, `resumed`, `finished`, `ended`),
time — written by the same `saveTour` call, plus `app_user.tour_finished_at`.

- `TourProgress` stays the resume bookmark; the events are history, appended and never updated.
- Write the event in the API from the change between the stored step and the new one, so the web app needs no extra
  call and an old tab cannot invent history.
- Keep `step` numbers stable, or store the step's name: `lib/tour.ts` already versions its step list, and a renumbered
  step would otherwise mix two steps in one chart.

**Admin view:** a step-by-step drop-off chart next to the funnel in [03-analytics.md](03-analytics.md) — of 100 users who
started, how many reached each step, how long each step took, and where paused tours were left. Steps where many people
stop are the ones to shorten, explain better or drop.

## 2. Bring people back to a paused tour

- **In the app:** while a tour is paused, show a small "Continue the tour — step 10 of 19, The recipe Store" card on the
  workspaces page, instead of relying on the user to find it.
- **By email:** the demo lasts four hours, so a reminder the next day is too late. Send one email about 30 minutes after
  a tour stops mid-way while its demo still has time left, linking to `/?tour`. It is part of
  [07-lifecycle-email.md](07-lifecycle-email.md) and respects the same mail preference.
- **After the demo is gone:** a tour whose workspace was deleted cannot resume. Say so instead of pointing at a
  missing workspace, and offer to finish it on a paid workspace with a first top-up.

## 3. End the tour on the next step, not a goodbye — done

The last screen is "You built a website!", with confetti, a list of what the user just did and **Keep this workspace**
as its main action; "put it on your own domain" follows as a link. The plan as first written is kept below.

The last card currently suggests the GoDaddy domain recipe and the docs. At that moment the user has just built a
public page and has a few hours of demo left — the best moment to convert.

**Recommendation:** the finishing card offers, in this order:

1. **Keep this workspace** ([04-demo-conversion.md](04-demo-conversion.md)) — the page they just built stays online.
2. **Put it on your own domain** (the existing domain recipe).
3. **Invite a friend** ([13-referrals.md](13-referrals.md)).

## 4. Cut the steps that cost the most users — started

The Store's Private, Own and Public steps are skipped: opening the Store goes straight to the recipe, so a tour is 16
steps. They keep their numbers, so a tour paused on one resumes and recorded steps stay comparable. The copy is
rewritten for someone non-technical: no VM, CPU or connector vocabulary, and the four-hour limit said once, as "free
for four hours, keep it anytime". Progress shows as four named chapters, the page's own link opens from the tour once
the assistant gives it, and "Now make it yours" offers one-tap ideas. Further cuts wait for the step history.

The tour has 19 steps in four chapters and already shows the chapter and "10/19" with a progress bar. Once the step
history from (1) exists, merge or drop the steps where most users stop. The Store chapter (Private, Own, Public, Find)
is the likeliest candidate: four steps explain recipes before the user has seen one do anything.

## Measure

Share of sign-ups that start the tour, finish it, and pay within 7 days, split by whether they finished — that tells
whether the tour is worth improving further.
