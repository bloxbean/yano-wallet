import { defineConfig } from "vite";
import { nodePolyfills } from "vite-plugin-node-polyfills";
import path from "path";

// MeshJS needs Node polyfills (Buffer etc.) in the browser. Mirrors the working
// yaci-devkit meshjs example.
export default defineConfig({
  // GitHub Pages serves a project site from a subpath, so the built asset URLs
  // must carry it — the default "/" 404s there. Overridden by the deploy
  // workflow; local dev and local builds stay at the root.
  base: process.env.DEMO_BASE || "/",
  plugins: [nodePolyfills()],
  build: {
    commonjsOptions: {
      // Mesh's CBOR dependencies are CommonJS with circular requires. Rollup's
      // default hoists them, so a circular require can return undefined at the
      // moment a class needs it as a base — "Class extends value undefined",
      // thrown while the bundle evaluates, before any of our code runs. Only
      // production builds are affected: `vite dev` serves modules unbundled, in
      // the natural order. strictRequires wraps every CJS module in a function
      // so requires run in the order the code asks for them.
      strictRequires: true,
    },
  },
  server: {
    proxy: {
      // Koios preprod via the dev server (server-to-server) — browser fetches to
      // Koios get blocked (CORS/Cloudflare), which surfaced as "Failed to fetch".
      "/koios": {
        target: "https://preprod.koios.rest/api/v1",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/koios/, ""),
      },
      // Yaci DevKit's yaci-store (ADR-038). Proxied for the same reason as
      // Koios: a browser fetch from :3000 to :8080 is cross-origin. Override the
      // target with YACI_STORE_URL when devkit runs on a non-default port.
      "/yaci": {
        target: process.env.YACI_STORE_URL || "http://localhost:8080/api/v1",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/yaci/, ""),
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
