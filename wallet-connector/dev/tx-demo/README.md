# Yano signTx test (MeshJS)

A self-contained **signTx + submitTx** test for the Yano CIP-30 connector
(ADR-035, CIP30-M2): it builds a real "send ₳2 to yourself" transaction with
MeshJS, then signs and submits it through Yano.

It runs against **preprod** or a local **Yaci DevKit** devnet (ADR-038), chosen
with the Network selector. Every CIP-30 call is identical on both — only where
chain data comes from changes, which is the point: the same dApp code drives a
public testnet and a devnet you own.

MeshJS needs a bundler + Node polyfills (it can't be loaded from a bare CDN),
so this is a small Vite app — mirroring the yaci-devkit `meshjs-mint-nft`
example. No API key: the wallet supplies the inputs, and a simple send uses
Mesh's default protocol parameters, so no chain provider is queried.

## Run

```bash
cd wallet-connector/dev/tx-demo
npm install
npm run dev        # serves http://localhost:3000
```

Open http://localhost:3000, pick the network, **Connect Yano** (approve in the
app), then **Send ₳2 to myself**. Watch the status line:

```
Building transaction (send ₳2 to addr_test1…)
Waiting for signature — approve in Yano…      ← the sign approval pops in the wallet
Submitting…
Submitted ✓ <txhash>
```

### Preprod

Prerequisites: the extension loaded (`wallet-connector/`), and the wallet
running, unlocked, on **preprod with some tADA**.

### Yaci DevKit

Prerequisites: DevKit running with **yaci-store on `:8080`**, and the wallet
connected to the **yaci-devkit** network (Connect screen → Yaci DevKit; it is
external-only, since the wallet launches a Yano node, not yaci-store). Fund the
wallet's address from DevKit's faucet.

If DevKit serves yaci-store somewhere else, point the dev server at it:

```bash
YACI_STORE_URL=http://localhost:9090/api/v1 npm run dev
```

Both networks are reached through the Vite dev-server proxy rather than
browser-direct: Koios blocks browser calls (CORS/Cloudflare), and a fetch from
`:3000` to `:8080` is cross-origin too. See `vite.config.ts`.

> **Both testnets report CIP-30 `networkId` 0**, so the demo cannot detect that
> the wallet is on a different chain than the selector says. Changing the
> selector therefore drops the connection and asks you to reconnect
> deliberately. If a send fails with missing UTxOs, check the wallet is on the
> network you picked.

## Plutus script test (hardware M4)

Two extra buttons exercise a real script transaction end to end:

1. **Lock ₳3 at script** — an ordinary tx whose output sits at the
   *always-succeeds* Plutus script address with a datum hash (derived from your
   wallet's payment key, so the unlock finds *your* UTxO). Wait ~1 block.
2. **Unlock from script** — the genuine Plutus transaction: script input +
   redeemer + your collateral + script-data-hash. On a hardware wallet the
   Ledger signs it in **PLUTUS mode** (expect the device's "unknown script"
   style warnings — that's normal for script txs).

The lock tx hash is remembered in localStorage, **keyed by network**, so a lock
made on preprod is never used to attempt an unlock on a devnet.

Cost models (needed for the script-data-hash, which covers the cost-model
"language views") come from the selected network — Koios `/epoch_params` on
preprod, yaci-store `/epochs/latest/parameters` on DevKit. Mesh's baked-in
defaults are stale against a live chain and produce `InvalidScriptDataHash`.
Redeemer ex-units use Mesh's default budget — plenty for always-succeeds —
because Koios's Ogmios evaluate passthrough rejects anonymous browser POSTs.

## If it fails

- **`Cannot find window.cardano.yano`** — extension not loaded, or opened as `file://`.
- **insufficient funds** — the wallet's address needs tADA (preprod) or a faucet
  top-up (DevKit).
- **`Failed to fetch` / proxy errors on DevKit** — yaci-store isn't on `:8080`;
  set `YACI_STORE_URL`.
- **an error from `signTx`/`submitTx`** — that's the real M2 signal; capture it.
