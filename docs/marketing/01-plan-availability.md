# Plans people can actually buy

Every visitor the rest of this folder brings in is wasted if no plan can be created. Workspaces are sold as three plans,
each pinned to one Hetzner server type (`workspaced.plans`), and Hetzner sells those types out for hours at a time: on
2026-11-24 cx23, cx33 and cx43 were all sold out in nbg1, so no workspace and no free demo could be created there.

## Done

- **Fallback locations** in the VM manager (`WORKSPACED_HETZNER_FALLBACK_LOCATIONS`, `fsn1` by default): a type sold out
  in nbg1 is offered and created in the first fallback that has it, on the same server at the same cost.
- **No stand-in at the plan's price.** A pricier server behind a plan would sell at a loss (cpx22 at Starter's price
  loses about €17 a month), so a plan sold out everywhere shows as unavailable instead.

## Next

- **Alert when every plan is sold out.** An admin notification and email from the API when `/vm-sizes` has no available
  plan for longer than, say, 15 minutes, so it is noticed before users report it.
- **Add `hel1`** to the fallbacks if fsn1 runs short too. Helsinki is in the EU and priced the same; latency from
  western Europe is a little higher.
- **"Tell me when it's back"** on a sold-out plan: one email when it becomes available, reusing the funds-reminder mail.
- **A fourth plan on another server line** (for example cpx22 at its own guard-passing price, about €1.10 a day) only if
  sell-outs keep happening across all locations.

## Measure

Hours per week with no plan available, and create attempts refused as sold out.
