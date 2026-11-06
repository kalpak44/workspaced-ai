import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { InstructionsEditor } from "../src/components/molecules/InstructionsEditor";

/** The editor as a page holds it: the Markdown in state, updated on every change. */
function Editor() {
  const [value, setValue] = useState("");
  return <InstructionsEditor value={value} onChange={setValue} placeholder="An example" />;
}

describe("instructions editor", () => {
  it("round trips raw Markdown through the rich editor without losing unsaved edits", async () => {
    const user = userEvent.setup();
    render(<Editor />);
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const markdown = "## Working style\n\nUse **clear language**.\n\n- Run tests\n- Be concise";
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: { value: markdown } });
    await user.click(screen.getByRole("button", { name: "Editor" }));
    expect(screen.getByRole("textbox", { name: "AI instructions editor" })).toHaveTextContent("Working style");
    expect(screen.getByText("clear language").tagName).toBe("STRONG");
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue(markdown);
  });

  it("updates the formatted document and serializes real edits as Markdown", async () => {
    const user = userEvent.setup();
    render(<Editor />);
    const textbox = screen.getByRole("textbox", { name: "AI instructions editor" });
    // ProseMirror observes DOM mutations, like a native browser edit.
    await act(async () => {
      textbox.innerHTML = "<h2>Preferences</h2><p>Write <strong>clearly</strong>.</p>";
      fireEvent.input(textbox);
    });
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "AI instructions Markdown" })).toHaveValue("## Preferences\n\nWrite **clearly**."));
  });

  it("preserves tables, checklists, and code when formatted text is edited", async () => {
    const user = userEvent.setup();
    render(<Editor />);
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    fireEvent.change(screen.getByRole("textbox", { name: "AI instructions Markdown" }), { target: {
      value: "| Rule | Value |\n| --- | --- |\n| Style | Clear |\n\n- [x] Run tests\n\n```sh\nnpm test\n```",
    } });
    await user.click(screen.getByRole("button", { name: "Editor" }));
    const textbox = screen.getByRole("textbox", { name: "AI instructions editor" });
    expect(textbox.querySelector("table")).toHaveTextContent("Clear");
    expect(textbox.querySelector('input[type="checkbox"]')).toBeChecked();
    await act(async () => {
      const paragraph = document.createElement("p");
      paragraph.textContent = "Be concise.";
      textbox.append(paragraph);
      fireEvent.input(textbox);
    });
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const source = (screen.getByRole("textbox", { name: "AI instructions Markdown" }) as HTMLTextAreaElement).value;
    expect(source).toContain("| Style | Clear |");
    expect(source).toContain("[x] Run tests");
    expect(source).toContain("```sh\nnpm test\n```");
    expect(source).toContain("Be concise.");
  });

  it("shows the example only while empty, as a placeholder that is never its value", async () => {
    const user = userEvent.setup();
    render(<Editor />);
    const textbox = screen.getByRole("textbox", { name: "AI instructions editor" });
    await waitFor(() => expect(textbox.querySelector("[data-placeholder]")?.getAttribute("data-placeholder")).toBe("An example"));
    await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
    const raw = screen.getByRole("textbox", { name: "AI instructions Markdown" });
    expect(raw).toHaveValue("");
    expect(raw).toHaveAttribute("placeholder", "An example");
  });
});
