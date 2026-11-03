import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // In the cluster the API shares this host under /api/v1; locally it runs on its own port.
    proxy: { "/api": { target: "http://localhost:8080", ws: true } },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["test/setup.ts"],
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      // lcov feeds Sonar; the floor lives here rather than in CI, so `npm test` alone
      // enforces it and a deleted workflow step cannot silently disable it.
      reporter: ["text", "lcov"],
      thresholds: { lines: 80 },
    },
  },
});
