// Fails the build when a page links to a local path the site does not serve, since
// nginx answers a broken link with the 404 page and nothing else would notice.
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2] ?? "dist";
const pages = readdirSync(root).filter((name) => name.endsWith(".html"));
const broken = [];

for (const page of pages) {
  const html = readFileSync(join(root, page), "utf8");
  for (const [, target] of html.matchAll(/(?:href|src)="(\/[^"#?]*)/g)) {
    // Mirrors nginx's try_files: the path itself, then path.html, then path/index.html.
    const path = join(root, target);
    const found = [path, `${path}.html`, join(path, "index.html")].some((p) => existsSync(p) && !p.endsWith("/"));
    if (!found && !(target === "/" && existsSync(join(root, "index.html")))) broken.push(`${page}: ${target}`);
  }
}

if (broken.length > 0) {
  console.error(`Broken local links:\n  ${broken.join("\n  ")}`);
  process.exit(1);
}
console.log(`Checked ${pages.length} pages, no broken local links.`);
