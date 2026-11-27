---
title: GitHub for your agent
summary: Install git and the GitHub CLI, connect them with a token kept in Secrets and an SSH key of this workspace, so the agent can clone, copy repositories, open pull requests and set Actions secrets for you.
category: devops
tags: [github, git, gh, ssh, pull-requests]
visibility: public
---

# GitHub for your agent

Give OpenCode the GitHub CLI (`gh`) and an SSH key of its own, so the user can later ask it to
clone and push repositories, copy them elsewhere, open pull requests and set Actions secrets.
The user only creates a token and adds it as a secret; you do all the rest yourself, and check
everything the user did before relying on it. Do each step in order and stop at the first check
that fails, telling the user exactly what to fix.

## 1. Ask the user to create a token

Walk the user through this in their browser:

1. Open [New personal access token (classic)](https://github.com/settings/tokens/new), name the
   token `workspaced-ai` and choose an expiry.
2. So the agent can do everything the user can, **tick every scope**: each top-level checkbox
   (`repo`, `workflow`, `write:packages`, `delete:packages`, `admin:org`, `admin:public_key`,
   `admin:repo_hook`, `admin:org_hook`, `gist`, `notifications`, `user`, `delete_repo`,
   `write:discussion`, `admin:enterprise`, `audit_log`, `codespace`, `copilot`, `project`,
   `admin:gpg_key`, `admin:ssh_signing_key`) selects everything under it. To give less, untick
   what the agent should not do; `repo`, `workflow`, `read:org`, `admin:public_key` and `user`
   are the minimum for this recipe.
3. Generate it, then add it in this workspace's **Settings → Secrets** as **`GH_TOKEN`**, press
   **Restart** and come back to this session.

Tell the user this token controls their whole account, including deleting repositories, so a
short expiry is wise; it can be revoked at any time on the same page. Organizations that use
SAML single sign-on need **Configure SSO → Authorize** next to the token. Never ask for the
token in chat or write it anywhere.

Meanwhile, tell the user in plain words how you will work, and that they can reply "OK" or
say what to change, now or any time later just by asking:

- Commits are made as **Workspaced AI Agent** `<ai-agent@workspaced.online>`.
- New repositories are **private**.
- You do what they ask, but ask first before anything risky: deleting, overwriting history,
  making something public or merging.
- You work in their own account and any organizations the token can reach.

Keep the user's changes, if any, for steps 3 and 5. Do not ask them anything else technical.

## 2. Check what the user did

When the user is back, check each item without printing the token, and report the result as a
checklist:

```sh
test -n "$GH_TOKEN" && echo 'GH_TOKEN is set' || echo 'GH_TOKEN is missing'
apk add github-cli
gh api -i user | grep -iE '^(x-oauth-scopes|github-authentication-token-expiration):'
gh api user --jq '.login'
```

- **Missing:** the secret was not added, has another name (names are case sensitive) or the
  workspace was not restarted.
- **`401`:** the token is wrong, expired or revoked. The user creates a new one, replaces the
  secret and restarts.
- **Scopes:** compare `x-oauth-scopes` with the minimum above (`admin:org` includes `read:org`).
  If one is missing, the user ticks it on the token's page on GitHub and saves; the token's
  value stays the same, so no restart is needed. Check again.
- **Expiry:** tell the user when the token expires.
- **Organizations:** list them with `gh api user/orgs --jq '.[].login'` and try
  `gh api orgs/<org>/repos --jq 'length'` on each. A `403` mentioning SAML means the user still
  has to press **Configure SSO → Authorize** next to the token.

## 3. Sign the tools in

Do this yourself; the user runs nothing:

```sh
gh auth status
gh auth setup-git
git config --global user.name "Workspaced AI Agent"
git config --global user.email "ai-agent@workspaced.online"
```

`gh` reads `GH_TOKEN` itself: do not run `gh auth login`. `gh auth setup-git` makes git use the
token for HTTPS remotes. Use another commit name or email only if the user asked for one.

## 4. Make and register an SSH key

Unless `~/.ssh/id_ed25519_github` already exists, make it and trust GitHub's published host
keys:

```sh
mkdir -p ~/.ssh && chmod 700 ~/.ssh
ssh-keygen -t ed25519 -N '' -C workspaced-ai -f ~/.ssh/id_ed25519_github
gh api meta --jq '.ssh_keys[]' | sed 's/^/github.com /' >> ~/.ssh/known_hosts
```

Add to `~/.ssh/config`:

```
Host github.com
  IdentityFile ~/.ssh/id_ed25519_github
  IdentitiesOnly yes
```

Register the public key on the user's account yourself, unless `gh ssh-key list` already shows
it:

```sh
gh ssh-key add ~/.ssh/id_ed25519_github.pub --title workspaced-ai
```

Only if the token is not allowed to, show the user the `.pub` file (never the private key) and
ask them to paste it, titled `workspaced-ai`, at [GitHub → Settings → SSH keys → New SSH key](https://github.com/settings/ssh/new),
then confirm with `gh ssh-key list` that it is there. Finally `ssh -T git@github.com` must answer
"successfully authenticated".

## 5. Final check and instructions

Ask for a repository to read, and check `git ls-remote git@github.com:<owner>/<repo>.git` and
`gh pr list --repo <owner>/<repo>` both answer. Change nothing. Show the user the final
checklist: token set and valid, account, scopes, expiry, SSH key
registered, SSH and HTTPS access.

Write `~/.config/workspaced/instructions/github.md` saying:

- `gh` and git are set up with the `GH_TOKEN` secret and `~/.ssh/id_ed25519_github`, for the
  account `<login>`, committing as `Workspaced AI Agent <ai-agent@workspaced.online>` unless the user
  chose otherwise. Never print the token. If GitHub answers `401`, the user replaces the
  secret and restarts.
- Copy a repository with its branches and tags:
  `git clone --bare <from> copy.git && git -C copy.git push <to> 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*'`.
  Create the target first with `gh repo create <owner>/<name> --private` (or `--public`
  if the user chose that).
- Pull requests: `gh pr create`, `gh pr view`, `gh pr diff`, `gh pr checks`.
- Actions secrets come from a workspace secret, never the chat:
  `printenv <NAME> | gh secret set <NAME> --repo <owner>/<repo>`.
- How to work, from step 1, including any change the user asked for. When the user later asks
  for another change (commit name or email, visibility, what to ask about), apply it with
  `git config --global` where it applies and update this file.

Finish with what was checked and examples to ask: "Open a pull request from my branch", "Copy
this repository to my account", "Set the DEPLOY_KEY secret on this repository".
