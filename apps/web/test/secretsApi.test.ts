import { describe, expect, it } from "vitest";
import { listModelProviders } from "../src/api/modelProvidersApi";
import { addSecret, listSecrets, removeSecret } from "../src/api/secretsApi";
import { installFakeApi, OPENROUTER } from "./fakeApi";

describe("secretsApi", () => {
  it("adds, lists and removes under the workspace's route", async () => {
    const api = installFakeApi();
    expect(await addSecret("starter", "TOKEN", "v")).toEqual({ name: "TOKEN", managed: false });
    expect(await listSecrets("starter")).toEqual([
      { name: "OPENROUTER_API_KEY", managed: true },
      { name: "TOKEN", managed: false },
    ]);
    await removeSecret("starter", "TOKEN");
    expect(api.secrets.get("starter")).toEqual([]);
    expect(String(api.apiCalls()[0][0])).toBe("/api/v1/workspaces/starter/secrets");
  });

  it("refuses a name or workspace id that would escape the route", async () => {
    const api = installFakeApi();
    await expect(removeSecret("starter", "../x")).rejects.toThrow("Invalid secret name");
    await expect(listSecrets("a/b")).rejects.toThrow("Invalid workspace id");
    expect(api.fetchFn).not.toHaveBeenCalled();
  });
});

describe("modelProvidersApi", () => {
  it("lists the providers", async () => {
    installFakeApi();
    expect(await listModelProviders()).toEqual([OPENROUTER]);
  });
});
