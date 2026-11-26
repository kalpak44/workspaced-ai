import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SessionNavigation } from "../src/components/organisms/SessionNavigation";

const sessions = [
  { id: "mine", title: "My work", path: "/mine", createdAt: "2026-11-04T10:00:00Z" },
  { id: "run", title: "Task run", path: "/run", createdAt: "2026-11-04T10:05:00Z", task: "watch" },
];

describe("session navigation", () => {
  it("defaults to your sessions and follows navigation after the list loads", async () => {
    const props = { loading: false, deleting: new Set<string>(), onOpen: vi.fn(), onDelete: vi.fn() };
    const { rerender } = render(<SessionNavigation {...props} loading sessions={[]} selectedId="mine" />);
    rerender(<SessionNavigation {...props} sessions={sessions} selectedId="mine" />);
    expect(screen.getByRole("combobox")).toHaveValue("user");
    expect(screen.queryByRole("button", { name: "Task run" })).not.toBeInTheDocument();
    await userEvent.setup().type(screen.getByRole("searchbox"), "missing");
    rerender(<SessionNavigation {...props} sessions={sessions} selectedId="run" />);
    expect(screen.getByRole("combobox")).toHaveValue("scheduled");
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Task run" })).toHaveAttribute("aria-current", "page");
  });

  it("keeps the selected filter when a new scheduled run arrives", async () => {
    const props = { loading: false, selectedId: "mine", deleting: new Set<string>(), onOpen: vi.fn(), onDelete: vi.fn() };
    const { rerender } = render(<SessionNavigation {...props} sessions={sessions} />);
    await userEvent.setup().selectOptions(screen.getByRole("combobox"), "scheduled");
    rerender(<SessionNavigation {...props} sessions={[...sessions, { ...sessions[1]!, id: "run2" }]} />);
    expect(screen.getByRole("combobox")).toHaveValue("scheduled");
    expect(screen.getAllByRole("button", { name: "Task run" })).toHaveLength(2);
  });
});
