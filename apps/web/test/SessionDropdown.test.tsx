import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SessionDropdown } from "../src/components/organisms/SessionDropdown";

const sessions = [
  { id: "plan", title: "Project plan", path: "/plan", createdAt: "2026-11-04" },
  { id: "review", title: "Code review", path: "/review", createdAt: "2026-11-04" },
  { id: "unavailable", title: "Archived work", path: "/archive", createdAt: "2026-11-04", available: false },
];

function setup() {
  const onOpen = vi.fn();
  const onDelete = vi.fn();
  render(<SessionDropdown sessions={sessions} selectedId="plan" loading={false} deleting={new Set()} onOpen={onOpen} onDelete={onDelete} />);
  return { user: userEvent.setup(), onOpen, onDelete, trigger: screen.getByRole("button", { name: "Choose session" }) };
}

describe("mobile session dropdown", () => {
  it("keeps scheduled runs separate and opens their group for the current session", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(<SessionDropdown sessions={[...sessions,
      { id: "run1", title: "Approval watch", path: "/run1", createdAt: "2026-11-04T10:00:00Z", task: "approval-watch" },
      { id: "run2", title: "Approval watch", path: "/run2", createdAt: "2026-11-04T10:05:00Z", task: "approval-watch" },
    ]} selectedId="run2" loading={false} deleting={new Set()} onOpen={onOpen} onDelete={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Choose session" }));
    expect(screen.getByRole("combobox", { name: "Sessions shown" })).toHaveValue("scheduled");
    expect(screen.queryByRole("button", { name: "Project plan" })).not.toBeInTheDocument();
    const runs = screen.getAllByRole("button", { name: "Approval watch" });
    expect(runs[0]).toHaveAttribute("aria-current", "page");
    expect(runs[0].querySelector("time")).toHaveAttribute("dateTime", "2026-11-04T10:05:00Z");
    await user.type(screen.getByRole("searchbox"), "approval-watch");
    expect(screen.getAllByRole("button", { name: "Approval watch" })).toHaveLength(2);
    await user.selectOptions(screen.getByRole("combobox"), "user");
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.queryByRole("button", { name: "Approval watch" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Project plan" }));
    expect(onOpen).toHaveBeenCalledWith("plan");
  });

  it("searches all sessions and switches to a match without deleting it", async () => {
    const { user, onOpen, onDelete, trigger } = setup();
    expect(trigger).toHaveTextContent("Project plan");
    expect(screen.queryByRole("region", { name: "Session picker" })).not.toBeInTheDocument();
    await user.click(trigger);
    const search = screen.getByRole("searchbox", { name: "Search sessions" });
    expect(search).toHaveFocus();
    await user.type(search, "REVIEW");
    expect(screen.queryByRole("button", { name: "Project plan" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Code review" }));
    expect(onOpen).toHaveBeenCalledWith("review");
    expect(onDelete).not.toHaveBeenCalled();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  it("handles empty search, Escape, and outside dismissal, resetting the search", async () => {
    const { user, trigger } = setup();
    await user.click(trigger);
    await user.type(screen.getByRole("searchbox"), "missing");
    expect(screen.getByText("No matching sessions.")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    await user.click(trigger);
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Archived work" })).toBeDisabled();
    await user.click(document.body);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("requests deletion separately from opening a session", async () => {
    const { user, trigger, onDelete, onOpen } = setup();
    await user.click(trigger);
    await user.click(within(screen.getByRole("region", { name: "Session picker" })).getByRole("button", { name: "Delete session: Code review" }));
    expect(onDelete).toHaveBeenCalledWith("review");
    expect(onOpen).not.toHaveBeenCalled();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });
});
