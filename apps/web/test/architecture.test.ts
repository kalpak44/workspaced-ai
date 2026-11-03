import { describe, expect, it } from "vitest";

// The atomic layers, lowest first. A layer may use any layer below it, never one above.
const LAYERS = ["atoms", "molecules", "organisms", "templates", "pages"] as const;

const sources = import.meta.glob<string>("../src/**/*.{ts,tsx}", {
  query: "?raw",
  import: "default",
  eager: true,
});

/** Every relative import of a source file, resolved to a path under src/. */
function importsOf(file: string, source: string): string[] {
  return [...source.matchAll(/from "(\.[^"]+)"/g)].map(
    ([, spec]) => new URL(spec, `file:///${file.replace("../", "")}`).pathname.slice(1),
  );
}

const layerOf = (path: string) => LAYERS.findIndex((l) => path.includes(`/components/${l}/`) || path.endsWith(`/components/${l}`));

describe("architecture", () => {
  const files = Object.entries(sources).map(([path, source]) => ({
    file: path.replace("../", ""),
    imports: importsOf(path, source),
  }));

  it("finds the sources it checks", () => {
    expect(files.length).toBeGreaterThan(10);
  });

  it("keeps every component layer to the layers below it", () => {
    const violations = files.flatMap(({ file, imports }) => {
      const own = layerOf(file);
      return own < 0 ? [] : imports.filter((i) => layerOf(i) > own).map((i) => `${file} -> ${i}`);
    });
    expect(violations).toEqual([]);
  });

  it("keeps atoms free of hooks", () => {
    const violations = files
      .filter(({ file }) => layerOf(file) === 0)
      .flatMap(({ file, imports }) => imports.filter((i) => i.includes("src/hooks/")).map((i) => `${file} -> ${i}`));
    expect(violations).toEqual([]);
  });

  it("reaches the API only through hooks", () => {
    const violations = files
      .filter(({ file }) => !file.includes("src/hooks/") && !file.includes("src/api/"))
      .flatMap(({ file, imports }) => imports.filter((i) => i.includes("src/api/")).map((i) => `${file} -> ${i}`));
    expect(violations).toEqual([]);
  });
});
