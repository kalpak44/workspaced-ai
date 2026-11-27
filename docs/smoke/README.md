# ChatGPT subscription smoke test

This is a local experiment for the OpenCode provider used by Workspaced, not a
Workspaced account connector or a deployment change. It does not change the
OpenRouter provider, trial, or billing behavior.

Run from a trusted single-user computer with OpenCode 2.2.5 (the harness pin) and
`jq` installed. Use `OPENCODE_BIN=/absolute/path/to/opencode` to select a specific
binary.

```sh
./docs/smoke/chatgpt-subscription.sh check
# Example model ID: use a model available to the ChatGPT account.
./docs/smoke/chatgpt-subscription.sh run openai/gpt-6.1-sol
```

`check` validates the isolated config and reads empty session metrics without
signing in. `run` opens OpenCode's **Sign in with ChatGPT** flow. Complete it in
your browser, allow plan usage, and use an account eligible for the integration.
The script makes one local OpenCode run and reports nonzero input and output
tokens. Those are Workspaced-side usage measurements, not plan credits, spend,
or remaining allowance. Review [ChatGPT app usage and limits][usage] separately.

The script uses a temporary home and empty project, does not inherit provider
keys, allows only the OpenAI provider in its config, and removes its local files
on exit. Removing local files is not revocation; disconnect the app in ChatGPT
settings if you no longer want it authorized. Do not run this on a shared
Workspaced VM or copy credentials from it into the deployed app.

Before enabling a hosted connector, confirm [eligibility for the hosted
deployment][eligibility]. Keep provider credentials in a trusted service,
outside the workspace shell, isolate ownership per account, and test reconnect,
revocation, and quota handling. Do not fall back silently to OpenRouter or an
OpenAI API key.

[usage]: https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites
[eligibility]: https://developers.openai.com/siwc/token-sharing-open-source
