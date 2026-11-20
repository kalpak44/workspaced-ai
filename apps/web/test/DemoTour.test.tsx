import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChatComposer } from "../src/components/molecules/ChatComposer";
import { DemoTour } from "../src/components/organisms/DemoTour";
import { setTourProgress, TOUR_DESIGN_PROMPT, TOUR_TASK, useTourProgress } from "../src/lib/tour";
import { installFakeApi } from "./fakeApi";
import * as secretsApi from "../src/api/secretsApi";
import * as workspacesApi from "../src/api/workspacesApi";
import * as vmSizesApi from "../src/api/vmSizesApi";
import * as tourActions from "../src/hooks/useDemoTour";

// Exercise the actual tour, persistence and API calls, replacing only the external login popup.
vi.mock("../src/hooks/useOpenRouterConnect", () => ({
  useOpenRouterConnect: (onKey: (key: string) => void) => ({
    status: "idle",
    connect: async (label: () => Promise<string | null>) => { if (await label()) onKey("private-openrouter-key"); },
  }),
}));

const workspace: workspacesApi.Workspace = { id: "trial", name: "Trial", modelProvider: "openrouter", model: null, timeZone: "Europe/Sofia", status: "READY", restartRequired: false, demo: true, expiresAt: new Date(Date.now() + 14400000).toISOString() };
function Progress() { const progress = useTourProgress(); return <output data-testid="progress">{JSON.stringify(progress)}</output>; }
function mount(step = 0, options: { noWorkspace?: boolean } = {}) {
  setTourProgress({ step, ...(options.noWorkspace ? {} : { workspaceId: "trial", sessionId: "blog-session" }) });
  const onDraft = vi.fn().mockResolvedValue({ id: "trial" });
  const onLaunch = vi.fn().mockResolvedValue("created");
  render(<><Progress /><DemoTour workspaces={[workspace]} loading={false} onDraft={onDraft} onLaunch={onLaunch} /></>);
  return { onDraft, onLaunch };
}

beforeEach(() => { installFakeApi([workspace]); });

describe("guided WordPress trial", () => {
  it("connects OpenRouter before offering only the cheapest available machine", async () => {
    vi.spyOn(vmSizesApi, "listVmSizes").mockResolvedValue([
      { serverType: "expensive", monthlyGross: 20, available: true, cores: 8, memoryGb: 16, diskGb: 80 },
      { serverType: "small", monthlyGross: 5, available: true, cores: 2, memoryGb: 4, diskGb: 40 },
      { serverType: "sold-out", monthlyGross: 1, available: false, cores: 2, memoryGb: 2, diskGb: 20 },
    ] as vmSizesApi.VmSize[]);
    const { onDraft, onLaunch } = mount(0, { noWorkspace: true });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("dialog", { name: "Connect your OpenRouter account" })).toHaveTextContent("charged");
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByRole("radio", { name: "small" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "small" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "expensive" })).toBeDisabled();
    expect(screen.getByRole("radio", { name: "sold-out" })).toBeDisabled();
    expect(onDraft).toHaveBeenCalledWith(expect.objectContaining({ demo: true, modelProvider: "openrouter" }));
    expect(onLaunch).not.toHaveBeenCalled();
    const create = screen.getByRole("button", { name: "Create free workspace" });
    expect(create).toBeDisabled();
    await user.click(create);
    expect(onLaunch).not.toHaveBeenCalled();
    await user.click(screen.getByRole("checkbox", { name: /permanently deleted after 4 hours/ }));
    expect(create).toBeEnabled();
    await user.click(create);
    expect(onLaunch).toHaveBeenCalledWith("trial", "private-openrouter-key");
    expect(localStorage.getItem("workspaced.tour.v2")).not.toContain("private-openrouter-key");
  });

  it("stores credentials only as secrets, restarts, then applies the recipe", async () => {
    const add = vi.spyOn(secretsApi, "addSecret");
    const restart = vi.spyOn(workspacesApi, "restartWorkspace");
    const apply = vi.spyOn(tourActions, "applyTourRecipe").mockResolvedValue("recipe-session");
    mount(5);
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "Save secrets and restart" })).toBeDisabled());
    await user.type(screen.getByLabelText("WordPress admin username"), "myadmin");
    await user.type(screen.getByLabelText("WordPress admin password"), "my-unique-password");
    await user.click(screen.getByRole("button", { name: "Save secrets and restart" }));
    await screen.findByRole("dialog", { name: "Make the secrets available" });
    expect(add).toHaveBeenCalledWith("trial", "WORDPRESS_ADMIN_USER", "myadmin");
    expect(add).toHaveBeenCalledWith("trial", "WORDPRESS_ADMIN_PASSWORD", "my-unique-password");
    expect(add).toHaveBeenCalledWith("trial", "WORDPRESS_DB_PASSWORD", expect.stringMatching(/^[a-f0-9]{64}$/));
    expect(restart).toHaveBeenCalledOnce();
    expect(localStorage.getItem("workspaced.tour.v2")).not.toContain("my-unique-password");
    expect(localStorage.getItem("workspaced.tour.v2")).not.toContain("myadmin");
    await waitFor(() => expect(screen.getByRole("button", { name: "Next" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(apply).toHaveBeenCalledWith("trial");
    expect(await screen.findByRole("dialog", { name: "Watch the agent build your blog" })).toBeInTheDocument();
  });

  it("retries a partially saved secret without overwriting it", async () => {
    const add = vi.spyOn(secretsApi, "addSecret");
    add.mockResolvedValueOnce({ name: "WORDPRESS_ADMIN_USER", managed: false }).mockRejectedValueOnce(new Error("network"));
    mount(5);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("WordPress admin username"), "myadmin");
    await user.type(screen.getByLabelText("WordPress admin password"), "my-unique-password");
    await user.click(screen.getByRole("button", { name: "Save secrets and restart" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Saved secrets are kept");
    expect(screen.getByLabelText("WordPress admin username")).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Save secrets and restart" }));
    await screen.findByRole("dialog", { name: "Make the secrets available" });
    expect(add.mock.calls.filter((call) => call[1] === "WORDPRESS_ADMIN_USER")).toHaveLength(1);
  });

  it("creates sourced news before handing over the design change", async () => {
    vi.spyOn(tourActions, "scheduleTourTask").mockResolvedValue(true);
    mount(9);
    fireEvent.click(screen.getByRole("button", { name: "Create news task" }));
    await screen.findByRole("dialog", { name: "Your news task is scheduled" }, { timeout: 5000 });
    expect(tourActions.scheduleTourTask).toHaveBeenCalledWith("trial", "Europe/Sofia");
    expect(TOUR_TASK.prompt).toMatch(/source.*never invent news/i);
    await userEvent.setup().click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByRole("dialog", { name: "Your turn: change the design" })).toBeInTheDocument();
  });

  it("types a design suggestion but leaves Send to the user", async () => {
    const send = vi.fn().mockResolvedValue(true);
    mount(11);
    render(<ChatComposer busy={false} onSend={send} onStop={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Type an example for me" }));
    await screen.findByRole("dialog", { name: "Edit your request, then press Send" }, { timeout: 10000 });
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(TOUR_DESIGN_PROMPT);
    expect(screen.getByRole("button", { name: "Send" })).toBeEnabled();
    expect(send).not.toHaveBeenCalled();
    await userEvent.setup().click(screen.getByRole("button", { name: "Send" }));
    expect(send).toHaveBeenCalledWith(TOUR_DESIGN_PROMPT);
  });
  it("notifies on Finish, refreshes the bell and never emails", async () => {
    const api = installFakeApi([workspace]);
    mount(12);
    await userEvent.setup().click(screen.getByRole("button", { name: "Finish tutorial" }));
    await waitFor(() => expect(screen.getByTestId("progress")).toHaveTextContent("null"));
    expect(api.notifications.rows.filter((notification) => notification.title === "Tutorial completed")).toHaveLength(1);
    expect(api.mail.sent).toHaveLength(0);
  });

  it("keeps completion retryable after a failed request and does not notify on Skip", async () => {
    const api = installFakeApi([workspace]);
    api.state.fail = 503;
    mount(12);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Finish tutorial" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not save tutorial completion");
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(false);
    await user.click(screen.getByRole("button", { name: "Skip tour" }));
    expect(api.notifications.rows.some((notification) => notification.title === "Tutorial completed")).toBe(false);
  });

});
