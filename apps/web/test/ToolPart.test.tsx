import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { ChatMessage, ChatPart } from "../src/api/sessionsApi";
import { ChatMessageView } from "../src/components/molecules/ChatMessageView";

function renderTool(part: Partial<ChatPart>) {
  const message: ChatMessage = {
    id: "m",
    role: "assistant",
    parts: [{ id: "t", type: "tool", text: "shell", tool: "shell", status: "completed", ...part }],
  };
  render(<ul><ChatMessageView message={message} /></ul>);
}

describe("tool calls in the chat", () => {
  it("shows a shell call as one line that opens to its command and output", async () => {
    renderTool({ input: { command: "ls -la", description: "List the files" }, output: "a.txt\nb.txt" });
    expect(screen.getByText("List the files")).toBeInTheDocument();
    expect(screen.getByLabelText("Command")).not.toBeVisible();

    await userEvent.setup().click(screen.getByText("List the files"));
    expect(screen.getByLabelText("Command")).toHaveTextContent("$ ls -la");
    expect(screen.getByLabelText("Output")).toHaveTextContent("a.txt b.txt");
  });

  it("shows another tool's arguments, and a failure's reason", async () => {
    renderTool({ tool: "read", status: "error", input: { filePath: "/x" }, output: "no such file" });
    await userEvent.setup().click(screen.getByText("/x"));
    expect(screen.getByLabelText("Input")).toHaveTextContent('"filePath": "/x"');
    expect(screen.getByLabelText("Output")).toHaveTextContent("no such file");
  });

  it("stays a plain line while nothing is known yet", () => {
    renderTool({ status: "pending" });
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
    expect(screen.getByText("shell")).toBeInTheDocument();
  });
});
