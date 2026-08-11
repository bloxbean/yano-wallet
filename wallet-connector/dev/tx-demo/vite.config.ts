import { defineConfig } from "vite";
import { nodePolyfills } from "vite-plugin-node-polyfills";
import path from "path";

// MeshJS needs Node polyfills (Buffer etc.) in the browser. Mirrors the working
// yaci-devkit meshjs example.
export default defineConfig({
  plugins: [nodePolyfills()],
  server: {
    proxy: {
      // Koios preprod via the dev server (server-to-server) — browser fetches to
      // Koios get blocked (CORS/Cloudflare), which surfaced as "Failed to fetch".
      "/koios": {
        target: "https://preprod.koios.rest/api/v1",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/koios/, ""),
      },
    },
  },
  resolve: {
    alias: {
      // The ESM build of libsodium-wrappers-sumo has a broken relative import to
      // ./libsodium-sumo.mjs (the file lives in a different package). Force Vite
      // to use the working CJS build instead.
      "libsodium-wrappers-sumo": path.resolve(
        __dirname,
        "node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js"
      ),
    },
  },
});
