import { KoiosProvider, YaciProvider } from "@meshsdk/core";
import type { IFetcher } from "@meshsdk/core";

// Everything that differs between the chains this demo can run against. The
// CIP-30 flows themselves (connect, signTx, submitTx, lock, unlock) are
// identical — only where chain data comes from, and where a tx can be viewed,
// changes.
//
// Both providers are pointed at a Vite dev-server proxy prefix rather than at
// the upstream host: browser-direct calls to Koios are blocked (CORS /
// Cloudflare), and a browser fetch from :3000 to yaci-store on :8080 is
// cross-origin too. Proxying server-to-server sidesteps both — see vite.config.ts.

export type NetworkId = "preprod" | "yaci-devkit";

export interface NetworkConfig {
  id: NetworkId;
  label: string;
  /** How to reach chain data (a dev-server proxy prefix). */
  base: string;
  /** Mesh provider used as the fetcher for the script (unlock) flow. */
  provider: () => IFetcher;
  /** Current cost models as [PlutusV1, PlutusV2, PlutusV3]. */
  costModels: () => Promise<any[]>;
  /** A link to view a transaction, or null where no explorer exists. */
  explorerTx: (hash: string) => string | null;
  /** What the user must have running for this network to work. */
  hint: string;
}

/** Koios returns cost_models as either a JSON string or an object. */
function parseCostModels(raw: any): any {
  return typeof raw === "string" ? JSON.parse(raw) : raw;
}

/**
 * Cost models as the ordered [V1, V2, V3] arrays Mesh's builder needs.
 *
 * Strictly arrays — a keyed object is rejected, never converted. Blockfrost-shaped
 * APIs (yaci-store, Yano) publish cost models twice: `cost_models_raw`, the
 * ordered arrays the ledger actually hashes, and `cost_models`, the same numbers
 * keyed by parameter name for humans. Only the first is usable, so each caller
 * must read the right field (Koios has no `_raw` and returns arrays under
 * `cost_models`).
 *
 * Converting the keyed form with Object.values() would appear to work — key
 * order is canonical in practice — but nothing guarantees a server emits keys in
 * that order, and a mis-ordered cost model silently produces the WRONG
 * script-data-hash. That surfaces on-chain as an unrelated-looking rejection,
 * long after the mistake. Failing here instead keeps the cause visible.
 */
function ordered(models: any): any[] {
  const byLanguage = ["PlutusV1", "PlutusV2", "PlutusV3"].map((language) => models?.[language]);
  // A chain may not have every language yet; a missing trailing one is fine,
  // but a hole in the middle would shift V3 into V2's slot.
  while (byLanguage.length && byLanguage[byLanguage.length - 1] === undefined) {
    byLanguage.pop();
  }
  const bad = byLanguage.findIndex((model) => !Array.isArray(model));
  if (!byLanguage.length || bad >= 0) {
    throw new Error(
      "Unusable Plutus cost models (got keys: " +
        JSON.stringify(Object.keys(models ?? {})) +
        "). Expected ordered arrays — on a Blockfrost-shaped API read cost_models_raw, " +
        "not cost_models."
    );
  }
  return byLanguage;
}

export const NETWORKS: Record<NetworkId, NetworkConfig> = {
  preprod: {
    id: "preprod",
    label: "Preprod (Koios)",
    base: "/koios",
    provider: () => new KoiosProvider("/koios"),
    // Mesh's baked-in default cost models are stale vs live preprod, which the
    // node rejects as InvalidScriptDataHash — the script-data-hash covers the
    // cost-model "language views". Mesh's Protocol type cannot carry cost
    // models, so they are injected into the builder directly.
    // Koios has no cost_models_raw; its cost_models is already the array form.
    costModels: async () => {
      const epochs = await (
        await fetch("/koios/epoch_params?order=epoch_no.desc&limit=1")
      ).json();
      return ordered(parseCostModels(epochs[0].cost_models));
    },
    explorerTx: (hash) => `https://preprod.cardanoscan.io/transaction/${hash}`,
    hint: "Yano on preprod with some tADA",
  },

  "yaci-devkit": {
    id: "yaci-devkit",
    label: "Yaci DevKit (local devnet)",
    base: "/yaci",
    provider: () => new YaciProvider("/yaci"),
    // cost_models_raw, never cost_models: yaci-store is Blockfrost-shaped, so
    // cost_models is keyed by parameter name and cannot be used to build the
    // script-data-hash (see ordered()).
    costModels: async () => {
      const params = await (await fetch("/yaci/epochs/latest/parameters")).json();
      return ordered(parseCostModels(params.cost_models_raw));
    },
    // A devnet has no public explorer. DevKit's own viewer is optional and its
    // port varies, so show the bare hash rather than a link that may 404.
    explorerTx: () => null,
    hint: "Yaci DevKit running (yaci-store on :8080) and Yano connected to the yaci-devkit network",
  },
};

const STORE_KEY = "yano-demo-network";

export function selectedNetwork(): NetworkConfig {
  const saved = localStorage.getItem(STORE_KEY) as NetworkId | null;
  return (saved && NETWORKS[saved]) || NETWORKS.preprod;
}

export function selectNetwork(id: NetworkId) {
  localStorage.setItem(STORE_KEY, id);
}
