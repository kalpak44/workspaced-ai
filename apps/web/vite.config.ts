import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [tailwindcss()],
  server: {
    // In the cluster the API shares this host under /api/v1; locally it runs on its own port.
    proxy: { "/api": "http://localhost:8080" },
  },
});
