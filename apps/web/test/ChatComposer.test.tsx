import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ChatComposer } from "../src/components/molecules/ChatComposer";

class Speech {
  static latest: Speech;
  lang = "";
  continuous = false;
  interimResults = false;
  onresult: ((event: unknown) => void) | null = null;
  onerror: ((event: { error: string }) => void) | null = null;
  onend: (() => void) | null = null;
  start = vi.fn();
  stop = vi.fn(() => this.onend?.());
  abort = vi.fn(() => this.onend?.());
  constructor() { Speech.latest = this; }
  result(text: string) {
    act(() => this.onresult?.({ resultIndex: 0, results: [{ isFinal: true, 0: { transcript: text } }] }));
  }
}

describe("voice dictation", () => {
  it("appends recognized speech, keeps it for review and stops the microphone on unmount", async () => {
    vi.stubGlobal("SpeechRecognition", Speech);
    const user = userEvent.setup();
    const send = vi.fn().mockResolvedValue(true);
    const { unmount } = render(<ChatComposer busy={false} onSend={send} onStop={() => {}} />);
    await user.type(screen.getByRole("textbox"), "Please");
    await user.click(screen.getByRole("button", { name: "Dictate message" }));
    expect(Speech.latest.start).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
    Speech.latest.result("list the files");
    expect(screen.getByRole("textbox")).toHaveValue("Please list the files");
    expect(send).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Stop dictation" }));
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(send).toHaveBeenCalledWith("Please list the files");
    await user.click(screen.getByRole("button", { name: "Dictate message" }));
    unmount();
    expect(Speech.latest.abort).toHaveBeenCalled();
    expect(Speech.latest.onresult).toBeNull();
  });

  it("reports denied microphone access and retains the typed draft", async () => {
    vi.stubGlobal("webkitSpeechRecognition", Speech);
    const user = userEvent.setup();
    render(<ChatComposer busy={false} onSend={vi.fn()} onStop={() => {}} />);
    await user.type(screen.getByRole("textbox"), "Keep this");
    await user.click(screen.getByRole("button", { name: "Dictate message" }));
    act(() => Speech.latest.onerror?.({ error: "not-allowed" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Microphone access was denied");
    expect(screen.getByRole("textbox")).toHaveValue("Keep this");
    expect(screen.getByRole("button", { name: "Send" })).toBeEnabled();
  });

  it("hides the microphone when unsupported and stops listening when chat becomes busy", async () => {
    const send = vi.fn();
    const { rerender } = render(<ChatComposer busy={false} onSend={send} onStop={() => {}} />);
    expect(screen.queryByRole("button", { name: "Dictate message" })).not.toBeInTheDocument();
    vi.stubGlobal("SpeechRecognition", Speech);
    rerender(<ChatComposer busy={false} onSend={send} onStop={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Dictate message" }));
    rerender(<ChatComposer busy onSend={send} onStop={() => {}} />);
    expect(Speech.latest.abort).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Dictate message" })).toBeDisabled();
  });
});
