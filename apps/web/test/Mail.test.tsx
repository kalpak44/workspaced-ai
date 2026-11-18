import { render, screen } from "@testing-library/react";
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
  await user.type(screen.getByLabelText("From"), "support");
  await user.type(screen.getByLabelText("Sender name"), "workspaced support");
  await user.type(screen.getByLabelText("To"), to);
  await user.type(screen.getByLabelText("Subject"), "Hello");
  await user.type(screen.getByLabelText("Message"), "Hi Ada");
}

describe("mail", () => {
  it("sends as an address on the domain and keeps the sender for the next message", async () => {
    const api = installFakeApi();
    const user = userEvent.setup();
    await openMail();
    expect(screen.getByText("@workspaced.online")).toBeInTheDocument();
    expect(screen.getByText("noreply@workspaced.online")).toBeInTheDocument();

    await write(user, "ada@example.com, grace@example.com");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("Sent to ada@example.com, grace@example.com.")).toBeInTheDocument();
    expect(api.mail.sent).toEqual([{
      from: "support",
      fromName: "workspaced support",
      to: ["ada@example.com", "grace@example.com"],
      replyTo: null,
      subject: "Hello",
      text: "Hi Ada",
    }]);
    expect(screen.getByLabelText("From")).toHaveValue("support");
    expect(screen.getByLabelText("Subject")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Send" })).toBeDisabled();
  });

  it("shows Mailjet's reason and keeps the message", async () => {
    const api = installFakeApi();
    api.mail.refuse = "Mailjet failed: The sender is not verified";
    const user = userEvent.setup();
    await openMail();

    await write(user, "ada@example.com");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The sender is not verified");
    expect(screen.getByLabelText("Message")).toHaveValue("Hi Ada");
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
