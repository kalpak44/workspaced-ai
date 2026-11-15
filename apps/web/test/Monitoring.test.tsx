import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";
import { type MetricsResponse, toMetrics } from "../src/api/metricsApi";
import { formatMetric } from "../src/components/organisms/MetricChart";
import { formatRate } from "../src/lib/format";
import { FakeEventSource, installFakeApi } from "./fakeApi";

async function openMonitoring() {
  installFakeApi();
  render(<App />);
  await userEvent.setup().click(await screen.findByRole("button", { name: "Starter" }));
  await waitFor(() => expect(() => FakeEventSource.latest()).not.toThrow());
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Actions for Starter" }));
  await user.click(screen.getByRole("menuitem", { name: "Monitoring" }));
  await screen.findByRole("list", { name: "Now" });
  return user;
}

describe("monitoring", () => {
  it("opens from the workspace menu with the current values and every chart", async () => {
    await openMonitoring();
    expect(location.pathname).toBe("/workspaces/starter/monitoring");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Monitoring");
    const tiles = within(screen.getByRole("list", { name: "Now" })).getAllByRole("listitem");
    expect(tiles.map((tile) => tile.querySelector("span")?.textContent)).toEqual(["CPU", "Memory", "Disk", "Network"]);
    for (const title of ["CPU", "Disk throughput", "Disk IOPS", "Network traffic", "Network PPS"]) {
      expect(screen.getByRole("region", { name: title })).toBeInTheDocument();
    }
    expect(screen.queryByRole("region", { name: "Memory" })).not.toBeInTheDocument();
    expect(tiles[1]).toHaveTextContent("25%");
    expect(tiles[1]).toHaveTextContent("1.0 of 4 GB");
    expect(within(screen.getByRole("region", { name: "Disk IOPS" })).getByText("Write")).toBeInTheDocument();
    expect(screen.getByText("Updating live")).toBeInTheDocument();
  });

  it("reads every series at one moment from the keyboard", async () => {
    const user = await openMonitoring();
    const chart = screen.getByRole("region", { name: "Network traffic" });
    within(chart).getByRole("img").focus();
    await user.keyboard("{ArrowLeft}");
    const readout = within(chart).getByRole("status");
    expect(readout).toHaveTextContent("In");
    expect(readout).toHaveTextContent("Out");
    expect(readout.textContent).toMatch(/\d{2}:\d{2}:\d{2}/);
    await user.keyboard("{Escape}");
    expect(within(chart).queryByRole("status")).not.toBeInTheDocument();
  });

  it("switches to a longer range, labelled by day", async () => {
    const user = await openMonitoring();
    await user.selectOptions(screen.getByRole("combobox", { name: "Time range" }), "7d");
    expect(await screen.findByText("Showing last 7 days.", { exact: false })).toBeInTheDocument();
    expect(screen.queryByText("Updating live")).not.toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "CPU" })).getAllByText(/^[A-Z][a-z]{2} \d{1,2}$/).length).toBeGreaterThan(1);
  });

  it("drops a trailing sample Hetzner has not filled and holds the value across a gap", () => {
    const response: MetricsResponse = {
      cores: 2,
      memoryGb: 4,
      timestamps: ["2026-11-15T10:00:00Z", "2026-11-15T10:01:00Z", "2026-11-15T10:02:00Z", "2026-11-15T10:03:00Z"],
      series: { cpu: [10, null, 30, null], networkInBytes: [5, 6, 7, null] },
      memory: null,
    };
    const metrics = toMetrics(response);
    expect(metrics.timestamps).toEqual([Date.parse("2026-11-15T10:00:00Z"), Date.parse("2026-11-15T10:01:00Z"), Date.parse("2026-11-15T10:02:00Z")]);
    expect(metrics.metrics[0].series[0].values).toEqual([10, 10, 30]);
    expect(metrics.metrics.find((m) => m.id === "disk-iops")?.series[0].values).toEqual([0, 0, 0]);
    expect(metrics.memory).toBeNull();
  });

  it("prints values in the unit a reader expects", () => {
    expect(formatRate(0)).toBe("0 B/s");
    expect(formatRate(20_000)).toBe("20 KB/s");
    expect(formatRate(1_234_000)).toBe("1.2 MB/s");
    expect(formatMetric(4.25, "percent")).toBe("4.3%");
    expect(formatMetric(5, "iops")).toBe("5 IOPS");
  });
});
