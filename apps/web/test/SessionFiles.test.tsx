import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { safeFileName } from "../src/api/sessionFilesApi";
import { isSessionFileLink } from "../src/hooks/useSessionFiles";
import { installFakeApi } from "./fakeApi";

/** A session page with one finished conversation, opened by its URL. */
async function openSession(api: ReturnType<typeof installFakeApi>, reply = "Done.") {
  api.sessions.set("starter", [
    { id: "s-1", title: "Files", path: "/home/workspace/sessions/s-1", createdAt: new Date().toISOString() },
  ]);
  api.messages.set("s-1", [
    { id: "m1", role: "assistant", parts: [{ id: "t", type: "text", text: reply, tool: null, status: null }] },
  ]);
  globalThis.history.replaceState(null, "", "/workspaces/starter/sessions/s-1");
  render(<App />);
  await screen.findByText(reply.startsWith("Here") ? "report.pdf" : reply);
}

describe("session files", () => {
  it("cleans a name to what the API accepts", () => {
    expect(safeFileName("Résumé final.pdf")).toBe("R_sum_ final.pdf");
    expect(safeFileName("../.env")).toBe("env");
    expect(safeFileName("...")).toBe("file");
  });

  it("tells a link to a session file from a web link", () => {
    expect(isSessionFileLink("outbox/report.pdf")).toBe(true);
    expect(isSessionFileLink("/home/workspace/sessions/s-1/a.txt")).toBe(true);
    expect(isSessionFileLink("https://example.com")).toBe(false);
    expect(isSessionFileLink("mailto:a@b.c")).toBe(false);
    expect(isSessionFileLink("#top")).toBe(false);
    expect(isSessionFileLink("/etc/passwd")).toBe(false);
    expect(isSessionFileLink(undefined)).toBe(false);
  });

  it("uploads attached files and names them in the message", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openSession(api);
    const file = new File(["a,b\n1,2"], "data set.csv", { type: "text/csv" });
    await user.upload(screen.getByLabelText("Files to attach"), file);
    const attached = await screen.findByRole("list", { name: "Attached files" });
    await waitFor(() => expect(within(attached).queryByLabelText(/Uploading/)).not.toBeInTheDocument());
    expect(api.sessionFiles.has("s-1/uploads/data set.csv")).toBe(true);
    // Files alone are enough to send.
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(api.prompts).toEqual([{ sessionId: "s-1", text: "Attached files:\n- uploads/data set.csv" }]));
    expect(screen.queryByRole("list", { name: "Attached files" })).not.toBeInTheDocument();
  });

  it("waits for uploads before sending, and drops a removed file", async () => {
    const api = installFakeApi();
    let release = () => {};
    api.sessionFiles.holdUpload = new Promise((resolve) => { release = resolve; });
    const user = userEvent.setup();
    await openSession(api);
    await user.upload(screen.getByLabelText("Files to attach"), new File(["x"], "a.txt"));
    await user.type(screen.getByLabelText("Message"), "Read this");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: "Send" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Remove a.txt" }));
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(api.prompts).toEqual([{ sessionId: "s-1", text: "Read this" }]));
  });

  it("shows a file that could not be uploaded", async () => {
    const api = installFakeApi();
    api.sessionFiles.failUpload = 413;
    const user = userEvent.setup();
    await openSession(api);
    await user.upload(screen.getByLabelText("Files to attach"), new File(["x"], "huge.bin"));
    expect(await screen.findByText("· Larger than 100 MB")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("downloads a file the agent linked through a short-lived link", async () => {
    const api = installFakeApi();
    api.sessionFiles.add("s-1/outbox/report.pdf");
    const open = vi.spyOn(globalThis, "open").mockReturnValue(null);
    const user = userEvent.setup();
    await openSession(api, "Here it is: [report.pdf](outbox/report.pdf) and [docs](https://example.com)");
    expect(screen.getByRole("link", { name: "docs" })).toHaveAttribute("href", "https://example.com");
    await user.click(screen.getByRole("button", { name: "report.pdf" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://app/api/v1/files/token-outbox/report.pdf", "_blank", "noopener"));
    open.mockRestore();
  });

  it("says so when a linked file is not in the session", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openSession(api, "Here: [report.pdf](outbox/report.pdf)");
    await user.click(screen.getByRole("button", { name: "report.pdf" }));
    expect(await screen.findByText("The file outbox/report.pdf is not in this session.")).toBeInTheDocument();
  });
});
