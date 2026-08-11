# Yano signTx test (MeshJS)

A self-contained **signTx + submitTx** test for the Yano CIP-30 connector
(ADR-035, CIP30-M2): it builds a real "send ₳2 to yourself" transaction on
preprod with MeshJS, then signs and submits it through Yano.

MeshJS needs a bundler + Node polyfills (it can't be loaded from a bare CDN),
so this is a small Vite app — mirroring the yaci-devkit `meshjs-mint-nft`
example. No API key: the wallet supplies the inputs, and a simple send uses
Mesh's default protocol parameters, so no chain provider is queried.

## Run

Prerequisites: the Yano wallet running, unlocked, on **preprod with some tADA**,
and the extension loaded (`wallet-connector/`).

```bash
cd wallet-connector/dev/tx-demo
npm install
npm run dev        # serves http://localhost:3000
```

Open http://localhost:3000, **Connect Yano** (approve in the app), then
**Send ₳2 to myself**. Watch the status line:

```
Building transaction (send ₳2 to addr_test1…)
Waiting for signature — approve in Yano…      ← the sign approval pops in the wallet
Submitting…
Submitted ✓ <txhash>  + a cardanoscan link
```

## Plutus script test (hardware M4)

Two extra buttons exercise a real script transaction end to end:

1. **Lock ₳3 at script** — an ordinary tx whose output sits at the
   *always-succeeds* Plutus script address with a datum hash (derived from your
   wallet's payment key, so the unlock finds *your* UTxO). Wait ~1 block.
2. **Unlock from script** — the genuine Plutus transaction: script input +
   redeemer + your collateral + script-data-hash. On a hardware wallet the
   Ledger signs it in **PLUTUS mode** (expect the device's "unknown script"
   style warnings — that's normal for script txs).

The lock tx hash is remembered in localStorage between the two steps. Protocol
parameters (cost models, needed for the script-data-hash) come from **Koios
preprod** (`https://preprod.koios.rest/api/v1`, public + CORS; anonymous tier is
rate limited — retry if it 429s). Redeemer ex-units use Mesh's default budget —
plenty for always-succeeds — because Koios's Ogmios evaluate passthrough rejects
anonymous browser POSTs.

## If it fails

- **`Cannot find window.cardano.yano`** — extension not loaded, or opened as `file://`.
- **build error mentioning a fetcher / protocol params** — a simple send shouldn't
  need a chain provider, but if Mesh asks for one, add
  `fetcher: new KoiosProvider("preprod")` (import from `@meshsdk/core`) to the
  `new Transaction({...})` call in `src/main.ts`.
- **insufficient funds** — the wallet's address needs preprod tADA.
- **an error from `signTx`/`submitTx`** — that's the real M2 signal; capture it.
