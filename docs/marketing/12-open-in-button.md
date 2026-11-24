# "Open in workspaced" button

A badge for GitHub READMEs, like the old "Deploy to Heroku" button. Clicking it opens a workspace with the repository
cloned and the agent ready to run it. It spreads through other people's projects without effort from us.

## How it works

1. A maintainer adds a badge to the README:

   ```markdown
   [![Open in workspaced](https://workspaced.online/badge.svg)](https://ai.workspaced.online/open?repo=https://github.com/owner/repo)
   ```

2. A visitor clicks it, signs up if needed, and gets a demo workspace (or a paid one if they have balance) with the
   repository cloned into the home directory.
3. The agent's first message offers to install and run the project, reading its README.

## Implementation

- **Web:** an `/open` route that keeps `repo` (and an optional `ref` partner code) through sign-in and opens
  `CreateWorkspaceDialog` prefilled.
- **API:** an optional `repository` on `CreateWorkspaceRequest`. Accept only `https://github.com/<owner>/<repo>` (and
  later GitLab), validated strictly, since it ends up in a command on the VM. The clone runs as the workspace user after
  the harness starts.
- **Badge:** `apps/site/src/badge.svg`, plus a page `/open-in-workspaced` with copy-paste snippets for Markdown and HTML.
- **Partners:** a maintainer who adds `&ref=<code>` earns commission under [14-partners.md](14-partners.md). That gives them a
  reason to add the badge.

## Cost and abuse

Each click-through that signs up costs a demo, which is already capped to one per account. Watch for repositories that
are malware: the clone runs on our VMs. The existing per-VM firewall limits what it can reach, and the demo ends on its
own after four hours.

## Getting the first badges

Add the badge to our own public repositories, then offer it to maintainers of projects that are hard to set up locally
(self-hosted apps, AI tools), ideally with a partner code.
