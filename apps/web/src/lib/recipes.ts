/** Where the agent writes recipe drafts in a workspace, as its guide tells it to. */
export const DRAFTS_DIRECTORY = "~/.config/workspaced/recipes";

/** A draft's front matter: whatever the agent filled in, unchecked until it is published. */
export interface DraftFields {
  readonly title?: string;
  readonly summary?: string;
  readonly category?: string;
  readonly tags: readonly string[];
  readonly visibility?: "PUBLIC" | "PRIVATE";
  /** Set when the draft revises a recipe that is already in the store. */
  readonly recipe?: string;
}

export interface ParsedDraft {
  readonly fields: DraftFields;
  /** The Markdown after the front matter: the recipe's steps. */
  readonly body: string;
}

const FRONT_MATTER = /^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/;

function unquote(value: string): string {
  const trimmed = value.trim();
  const quoted = /^(["'])(.*)\1$/.exec(trimmed);
  if (!quoted) return trimmed;
  try {
    return quoted[1] === '"' ? (JSON.parse(trimmed) as string) : quoted[2];
  } catch {
    return quoted[2];
  }
}

// A trailing "# comment" ends the value, unless the value is quoted: then the quotes end it.
function withoutComment(value: string): string {
  const quoted = /^("(?:[^"\\]|\\.)*"|'[^']*')/.exec(value);
  return quoted ? quoted[1] : value.replace(/\s+#.*$/, "").trim();
}

/** "a, b" and "[a, b]" both mean two tags; tags are lowercase words joined by hyphens. */
export function parseTags(input: string): string[] {
  const list = input.trim().replace(/^\[|\]$/g, "");
  const tags = list
    .split(",")
    .map((tag) => unquote(tag).toLowerCase().replace(/\s+/g, "-"))
    .filter((tag) => tag !== "");
  return [...new Set(tags)];
}

/**
 * Reads a draft the agent wrote. Front matter is a flat `key: value` list, not full YAML: a line
 * that is not one is ignored, so a sloppy draft still imports and the editor shows what is missing.
 */
export function parseDraft(text: string): ParsedDraft {
  const match = FRONT_MATTER.exec(text);
  if (!match) {
    return { fields: { tags: [] }, body: text.trim() };
  }
  const values = new Map<string, string>();
  for (const line of match[1].split(/\r?\n/)) {
    const pair = /^([A-Za-z]+)\s*:\s*(.*)$/.exec(line);
    if (pair) values.set(pair[1].toLowerCase(), withoutComment(pair[2].trim()));
  }
  const visibility = unquote(values.get("visibility") ?? "").toUpperCase();
  const fields: DraftFields = {
    title: values.has("title") ? unquote(values.get("title") ?? "") : undefined,
    summary: values.has("summary") ? unquote(values.get("summary") ?? "") : undefined,
    category: values.has("category") ? unquote(values.get("category") ?? "").toLowerCase() : undefined,
    tags: parseTags(values.get("tags") ?? ""),
    visibility: visibility === "PUBLIC" || visibility === "PRIVATE" ? visibility : undefined,
    recipe: values.get("recipe") ? unquote(values.get("recipe") ?? "") : undefined,
  };
  return { fields, body: text.slice(match[0].length).trim() };
}

// A value is quoted whenever it could read as something else, a comment or a list for one.
function scalar(value: string): string {
  return /^[\w .,!?()/-]*$/.test(value) && value.trim() === value ? value : JSON.stringify(value);
}

/** The same format the agent writes, so a draft written here reads back unchanged. */
export function serializeDraft(fields: DraftFields, body: string): string {
  const lines = [
    fields.title !== undefined && `title: ${scalar(fields.title)}`,
    fields.summary !== undefined && `summary: ${scalar(fields.summary)}`,
    fields.category && `category: ${fields.category}`,
    `tags: [${fields.tags.join(", ")}]`,
    fields.visibility && `visibility: ${fields.visibility.toLowerCase()}`,
    fields.recipe && `recipe: ${fields.recipe}`,
  ].filter((line): line is string => typeof line === "string");
  return `---\n${lines.join("\n")}\n---\n\n${body.trim()}\n`;
}

/** A file name for a draft: the title's words, within the workspace's file-name rule. */
export function draftName(title: string): string {
  const slug = title
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 56)
    .replace(/-+$/, "");
  return slug || "recipe";
}

/** The first message of a session that applies a recipe; the recipe itself follows it whole. */
export function applyPrompt(title: string, author: string, content: string): string {
  return [
    `Apply this recipe to the workspace: "${title}" by ${author}.`,
    "",
    "Follow its steps in order. Ask me for anything it leaves to me, such as names, addresses or choices, before you act on it, and ask before anything that deletes or replaces existing data. Secrets go under the workspace's Secrets, never in this chat. When you are done, tell me what was set up and how to reach it.",
    "",
    "---",
    "",
    content.trim(),
  ].join("\n");
}

/** Starts a session in which the agent writes a new recipe draft with the user. */
export function draftPrompt(idea: string): string {
  return [
    `Write a recipe for the store with me: ${idea.trim()}`,
    "",
    `Save it as a draft in ${DRAFTS_DIRECTORY}/<a short name>.md, in the recipe format from your guide. Ask me whatever you need to make it complete, and tell me when it is ready to import from the store.`,
  ].join("\n");
}

/** Starts a session in which the agent revises a draft the app has just written for it. */
export function revisePrompt(name: string, title: string, change: string): string {
  const what = change.trim() ? `What I want changed: ${change.trim()}` : "Ask me what I want changed.";
  return [
    `Let's revise my recipe "${title}". It is in ${DRAFTS_DIRECTORY}/${name}.md: edit that file in place and keep its \`recipe:\` line.`,
    "",
    what,
    "",
    "Tell me when it is ready to import from the store.",
  ].join("\n");
}

/** "Ada Lovelace", or the username for someone who gave no name. */
export function authorName(author: { readonly username: string; readonly firstName: string; readonly lastName: string }): string {
  return [author.firstName, author.lastName].filter(Boolean).join(" ") || author.username;
}
