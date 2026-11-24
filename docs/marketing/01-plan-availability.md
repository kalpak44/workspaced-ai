# Plans people can actually buy

Every visitor the rest of this folder brings in is wasted if no plan can be created. Workspaces are sold as three plans,
each pinned to one Hetzner server type (`workspaced.plans`), and Hetzner sells those types out for hours at a time: on
2026-11-24 cx23, cx33 and cx43 were all sold out in nbg1, so no workspace and no free demo could be created there.

## Done

- **Fallback locations** in the VM manager (`WORKSPACED_HETZNER_FALLBACK_LOCATIONS`, `fsn1` by default): a type sold out
  in nbg1 is offered and created in the first fallback that has it, on the same server at the same cost.
- **No stand-in at the plan's price.** A pricier server behind a plan would sell at a loss (cpx22 at Starter's price
  loses about €17 a month), so a plan sold out everywhere shows as unavailable instead.
- **Starter Flex**, cpx22 at €1.15 a day, a `fallback` plan offered as the default only while every other plan is sold
  out. On 2026-11-24 cx23, cx33 and cx43 were sold out in nbg1, fsn1 and hel1 alike, so the location fallback alone
  left nothing to sell.

- **Admins are alerted** (`PlanOutages`): an in-app notification and an email to every admin once no plan, Starter Flex
  included, has been available for 15 minutes (`workspaced.plans.outage-alert-after`), and another when one is back. An
  unreachable VM manager is not counted as an outage.

## Next

- **"Tell me when it's back"** on a sold-out plan: one email when it becomes available, reusing the funds-reminder mail.

## Measure

Hours per week with no plan available, from the `plan_outage` table (one row per outage, with its start and end), and
create attempts refused as sold out.
