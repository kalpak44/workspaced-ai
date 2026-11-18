import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { installFakeApi } from "./fakeApi";

/** The Mail tab of the general settings, with its form loaded. */
async function openMail() {
  globalThis.history.replaceState(null, "", "/settings");
  render(<App />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Mail" }));
  return screen.findByRole("form", { name: "New message" });
}

async function write(user: ReturnType<typeof userEvent.setup>, to: string) {
  await user.type(screen.getByLabelText("To"), to);
  await user.type(screen.getByLabelText("Subject"), "Hello");
  await user.click(screen.getByRole("button", { name: "Raw Markdown" }));
  fireEvent.change(screen.getByRole("textbox", { name: "Message Markdown" }), { target: { value: "Hi **Ada**" } });
}

describe("mail", () => {
  it("offers only the senders there are, and sends as the one chosen", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openMail();

    const from = screen.getByLabelText("From");
    expect(within(from).getAllByRole("option").map((o) => o.textContent))
      .toEqual(["contact@workspaced.online", "support@workspaced.online", "noreply@workspaced.online"]);
    expect(screen.queryByLabelText("Sender name")).not.toBeInTheDocument();
    await user.selectOptions(from, "support");
    await write(user, "ada@example.com, grace@example.com");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("Sent to ada@example.com, grace@example.com.")).toBeInTheDocument();
    expect(api.mail.sent).toEqual([{
      from: "support",
      to: ["ada@example.com", "grace@example.com"],
      replyTo: null,
      subject: "Hello",
      text: "Hi **Ada**",
    }]);
    expect(screen.getByLabelText("From")).toHaveValue("support");
    expect(screen.getByLabelText("Subject")).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "Message Markdown" })).toHaveValue("");
  });

  it("edits the chosen sender's template and previews it before saving", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openMail();

    await user.selectOptions(screen.getByLabelText("From"), "support");
    expect(screen.getByRole("region", { name: "Template for support@workspaced.online" })).toBeInTheDocument();
    const signature = screen.getByLabelText("Signature");
    expect(signature).toHaveValue("Best regards,\nsupport");
    await user.clear(signature);
    await user.type(signature, "Talk soon");

    await write(user, "ada@example.com");
    await user.click(screen.getByRole("button", { name: "Preview" }));
    const frame = await screen.findByTitle("Message preview");
    expect(frame.getAttribute("srcdoc")).toContain("Talk soon");
    expect(frame).toHaveAttribute("sandbox", "");
    expect(api.mail.senders[1].signature).toBe("Best regards,\nsupport");

    await user.click(screen.getByRole("button", { name: "Save template" }));
    expect(await screen.findByText("Template saved.")).toBeInTheDocument();
    expect(api.mail.senders[1].signature).toBe("Talk soon");
    expect(screen.getByRole("button", { name: "Save template" })).toBeDisabled();

    await user.selectOptions(screen.getByLabelText("From"), "contact");
    expect(screen.getByLabelText("Signature")).toHaveValue("Best regards,\ncontact");
  });

  it("shows Mailjet's reason and keeps the message", async () => {
    const api = installFakeApi();
    api.mail.refuse = "Mailjet failed: The sender is not verified";
    const user = userEvent.setup();
    await openMail();

    await write(user, "ada@example.com");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The sender is not verified");
    expect(screen.getByRole("textbox", { name: "Message Markdown" })).toHaveValue("Hi **Ada**");
    expect(api.mail.sent).toEqual([]);
  });

  it("offers no sending until a mail account is set up", async () => {
    const api = installFakeApi();
    api.mail.configured = false;
    const user = userEvent.setup();
    await openMail();

    expect(screen.getByText(/No mail account is set up/)).toBeInTheDocument();
    await write(user, "ada@example.com");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("is not offered to someone who is not an admin", async () => {
    const api = installFakeApi();
    api.auth.account = { ...api.auth.account, role: "USER" };
    globalThis.history.replaceState(null, "", "/settings/mail");
    render(<App />);

    expect(await screen.findByRole("button", { name: "Profile" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mail" })).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "New message" })).not.toBeInTheDocument();
  });
});
