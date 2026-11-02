import { defineConfig } from "vite";
export default defineConfig({
  server: {
    proxy: { "/api": process.env.CULTUREHUB_API || "http://localhost:8080" },
  },
});
