// Shown in an empty instructions editor as an example of what to write. Never saved, and never
// given to the agent: the user's own words replace it.

export const GENERAL_INSTRUCTIONS_EXAMPLE = `For example:

## About me
I am a backend developer. I mostly work in Java and TypeScript.

## How to work
- Explain your plan in a few lines before a larger change.
- Run the tests before saying something works.
- Keep answers short, and skip the summary of what you did.`;

export const WORKSPACE_INSTRUCTIONS_EXAMPLE = `For example:

## This workspace
A Spring Boot API in ~/project/api and a React app in ~/project/web.

## Rules
- Use pnpm, not npm, in ~/project/web.
- Never push to main; open a branch and tell me its name.
- Treat the Postgres MCP server as read-only.`;
