# Workspace transfer

A user can hand a workspace to another account, which then owns it and pays for it. It makes building for someone else
a business: a freelancer or agency builds a client's site with the agent, attaches the client's domain, and hands the
running workspace over. With the builder recorded as the client's partner, the builder keeps earning on the client's
hosting under [14-partners.md](14-partners.md), so builders bring paying customers without any spending on ads.

## How it works

1. The owner opens the workspace's settings and chooses **Transfer to another account**, entering the recipient's email.
2. The recipient gets an email and a notification with a link. Accepting signs them up if needed.
3. Before accepting they see what they take over: the size and its daily price, the attached domains, and that they
   must connect their own model provider.
4. On accepting, the workspace is theirs from the next billing hour, and the previous owner loses access.
5. An offer expires after 7 days and can be withdrawn until it is accepted.

## What moves and what does not

| | On transfer |
|---|---|
| VM, home directory, running apps | Move with it; nothing is restarted except the harness |
| Attached domains and Domain Connect records | Move with it; the `<vm id>.vm.workspaced.online` name does not change |
| Firewall rules | Move with it |
| Model provider key | **Replaced:** the recipient connects their own before accepting, and the old one is deleted from Vault |
| The workspace's secrets (`secret/workspaced-ai/vms/<vm id>`) | **Deleted** unless the owner ticks "Include secrets" per secret; they are the owner's credentials |
| Chat sessions | **Not moved by default:** "Include chat history" is opt-in, since it can contain the owner's other work and pasted credentials |
| Balance and billing history | Stay with each account |

The recipient's balance must cover the week a new workspace needs to be created (see [06-billing.md](06-billing.md));
otherwise accepting asks for a top-up first.

## Attribution

- If the recipient has no referrer yet, the sender becomes their referrer: a partner under
  [14-partners.md](14-partners.md), or a user under [13-referrals.md](13-referrals.md). The usual rule holds, one
  attribution per customer, set once.
- The recipient sees this before accepting ("Sent by Ana, who earns a share of what you spend for 12 months"), which
  is what partner disclosure requires.
- No new reward and no new cost: the economics are those of [13-referrals.md](13-referrals.md) and
  [14-partners.md](14-partners.md).
- A transfer to an account whose card fingerprint matches the sender's attributes nothing, as in both programs.

## API

- **Migration:**
  - `workspace_transfer` (id, workspace_id, from_user_id, to_email, to_user_id, include_history, status
    pending/accepted/declined/withdrawn/expired, created_at, expires_at)
  - `workspace.owner_id` is `updatable = false` in `WorkspaceEntity`; transfer is the one place that changes it, and
    so are the user ids on the workspace's `billing_usage` row and its sessions.
- **Endpoints:** `POST /workspaces/{id}/transfers`, `DELETE /workspaces/{id}/transfers/{transfer}`,
  `GET /transfers/{id}` for the recipient, `POST /transfers/{id}/accept` with the recipient's model provider key or
  connector.
- **Accepting, in one transaction:** close the sender's billing hour, move ownership, start the recipient's billing
  from the next hour, bind attribution; then, after the commit, replace the provider key and delete unchosen secrets
  through the VM manager and restart the harness. A failure after the commit retries; it never hands the workspace
  back.
- **Demos and drafts cannot be transferred.** A demo is free and ends on its own; a draft has no VM.
- **Mail:** offer, accepted, declined and expired, through `MailService.notify`.

## Pages

- **Workspace settings:** a "Transfer" section with the email field, the secrets and history choices, and a pending
  offer with Withdraw.
- **Accept page** at `/transfers/<id>`: what is taken over, the price, the provider step reused from
  `CreateWorkspaceDialog`, and Accept.
- **Partner section:** "Workspaces handed over" and the customers they brought.

## Abuse

- A transfer is never forced: the recipient must accept, so nobody can be made to pay for a workspace they did not
  ask for.
- Only a running, paid workspace with no unpaid hours can be offered.
- At most 10 pending offers per account.

## Outside the API and web app

- `apps/site/src/terms.html`: transfer, that the recipient becomes the controller of what is on the VM, and what is
  deleted.
- `apps/site/src/privacy.html`: the sender learns whether the offer was accepted, nothing else about the recipient.
- `apps/site/src/index.html` or a page of its own: "Build sites for clients" as a use case, once partners exist.

## Order

1. Transfer between accounts, with no attribution: useful on its own for anyone handing work over.
2. Attribution, once [13-referrals.md](13-referrals.md) or [14-partners.md](14-partners.md) ships.
