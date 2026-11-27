---
title: GitLab for your agent
summary: Install git and the GitLab CLI, connect them with a token kept in Secrets and an SSH key of this workspace, so the agent can clone, copy repositories, open merge requests and set CI/CD variables for you.
category: devops
tags: [gitlab, git, glab, ssh, merge-requests]
visibility: public
---

# GitLab for your agent

Give OpenCode the GitLab CLI (`glab`) and an SSH key of its own, so the user can later ask it to
clone and push repositories, copy them elsewhere, open merge requests and set CI/CD variables.
The user only creates a token and adds it as a secret; you do all the rest yourself, and check
everything the user did before relying on it. Do each step in order and stop at the first check
that fails, telling the user exactly what to fix.

## 1. Ask the user to create a token

Walk the user through this in their browser:

1. Open [Personal access tokens](https://gitlab.com/-/user_settings/personal_access_tokens),
   select **Add new token**, name it `workspaced-ai` and set an expiry.
2. So the agent can do everything the user can, **tick every scope** in the list (`api`,
   `read_user`, `read_api`, `read_repository`, `write_repository`, `read_registry`,
   `write_registry`, `create_runner`, `manage_runner`, `k8s_proxy`, `ai_features` and any
   others shown). To give less, tick only `api`, the minimum for this recipe.
3. Create it, then add it in this workspace's **Settings → Secrets** as **`GITLAB_TOKEN`**, press
   **Restart** and come back to this session.

Tell the user this token controls their whole account, including deleting projects, so a short
expiry is wise; it can be revoked at any time on the same page. Never ask for the token in chat
or write it anywhere.

Meanwhile, tell the user in plain words how you will work, and that they can reply "OK" or
say what to change, now or any time later just by asking:

- Commits are made as **Workspaced AI Agent** `<ai-agent@workspaced.online>`.
- New projects are **private**.
- You do what they ask, but ask first before anything risky: deleting, overwriting history,
  making something public or merging.
- You work in their own account and any groups the token can reach.

Keep the user's changes, if any, for steps 3 and 5. Do not ask them anything else technical.

## 2. Check what the user did

When the user is back, check each item without printing the token, and report the result as a
checklist:

```sh
test -n "$GITLAB_TOKEN" && echo 'GITLAB_TOKEN is set' || echo 'GITLAB_TOKEN is missing'
apk add glab
glab api personal_access_tokens/self | jq '{name, active, scopes, expires_at}'
glab api user | jq -r '.username'
```

- **Missing:** the secret was not added, has another name (names are case sensitive) or the
  workspace was not restarted.
- **`401` or `active: false`:** the token is wrong, expired or revoked. The user creates a new
  one, replaces the secret and restarts.
- **Scopes:** `scopes` must include `api`. GitLab cannot add a scope to an existing token, so if
  it is missing the user creates a new token, replaces the secret and restarts. Check again.
- **Expiry:** tell the user when the token expires.
- **Groups:** list them with `glab api groups | jq -r '.[].full_path'`.

## 3. Sign the tools in

Do this yourself; the user runs nothing. `glab` reads `GITLAB_TOKEN` itself: do not run
`glab auth login`. Check `glab auth status`. To let git use the token for HTTPS remotes, write
`~/.local/bin/git-credential-gitlab-token` and make it executable:

```sh
#!/bin/sh
test "$1" = get || exit 0
echo username=oauth2
echo "password=$GITLAB_TOKEN"
```

Then:

```sh
git config --global credential.https://gitlab.com.helper gitlab-token
git config --global user.name "Workspaced AI Agent"
git config --global user.email "ai-agent@workspaced.online"
```

Use another commit name or email only if the user asked for one.

## 4. Make and register an SSH key

Unless `~/.ssh/id_ed25519_gitlab` already exists, make it:

```sh
mkdir -p ~/.ssh && chmod 700 ~/.ssh
ssh-keygen -t ed25519 -N '' -C workspaced-ai -f ~/.ssh/id_ed25519_gitlab
```

Run `ssh-keyscan gitlab.com | ssh-keygen -lf -` and compare the fingerprints with
[GitLab's published ones](https://docs.gitlab.com/user/gitlab_com/#ssh-host-keys-fingerprints);
only if they match, add `ssh-keyscan gitlab.com` to `~/.ssh/known_hosts`. Add to
`~/.ssh/config`:

```
Host gitlab.com
  IdentityFile ~/.ssh/id_ed25519_gitlab
  IdentitiesOnly yes
```

Register the public key on the user's account yourself, unless `glab ssh-key list` already
shows it:

```sh
glab ssh-key add ~/.ssh/id_ed25519_gitlab.pub --title workspaced-ai
```

Only if that is refused, show the user the `.pub` file (never the private key) and ask them to
paste it, titled `workspaced-ai`, at [GitLab → Edit profile → SSH Keys](https://gitlab.com/-/user_settings/ssh_keys), then
confirm with `glab ssh-key list` that it is there. Finally `ssh -T git@gitlab.com` must answer
"Welcome to GitLab".

## 5. Final check and instructions

Ask for a project to read, and check `git ls-remote git@gitlab.com:<group>/<project>.git` and
`glab mr list --repo <group>/<project>` both answer. Change nothing. Show the user the final
checklist: token set and active, account, scopes, expiry, SSH key
registered, SSH and HTTPS access.

Write `~/.config/workspaced/instructions/gitlab.md` saying:

- `glab` and git are set up with the `GITLAB_TOKEN` secret and `~/.ssh/id_ed25519_gitlab`, for
  the account `<username>`, committing as `Workspaced AI Agent <ai-agent@workspaced.online>`
  unless the user chose otherwise. Never print the token. If GitLab
  answers `401`, the user replaces the secret and restarts.
- Copy a repository with its branches and tags:
  `git clone --bare <from> copy.git && git -C copy.git push <to> 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*'`.
  Create the target first with `glab repo create <group>/<name> --private` (or `--public`
  if the user chose that).
- Merge requests: `glab mr create`, `glab mr view`, `glab mr diff`, `glab ci status`.
- CI/CD variables come from a workspace secret, never the chat:
  `printenv <NAME> | glab variable set <NAME> --masked --repo <group>/<project>`.
- How to work, from step 1, including any change the user asked for. When the user later asks
  for another change (commit name or email, visibility, what to ask about), apply it with
  `git config --global` where it applies and update this file.

Finish with what was checked and examples to ask: "Open a merge request from my branch", "Copy
this GitHub repository into my GitLab group", "Set the DEPLOY_KEY variable on this project".
