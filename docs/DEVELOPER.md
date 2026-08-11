# Yano Wallet — Developer Guide

A full-node Cardano desktop wallet (ADR-033). The wallet is a **client** of a
Yano node's Blockfrost-compatible REST API; it can **launch and manage** a
local node itself, or connect to an external one. Keys never leave the wallet;
the node the wallet queries is the node you run — trustless by construction.

- Design: [`adr/in-progress/033-full-node-desktop-wallet.md`](../../adr/in-progress/033-full-node-desktop-wallet.md)
- Progress + decisions: [`adr/in-progress/033-full-node-wallet-tracker.md`](../../adr/in-progress/033-full-node-wallet-tracker.md)

## Modules

| Module | Purpose | Depends on |
|---|---|---|
| `wallet-core` | Pure domain: Argon2id vault, wallet/account model, `WalletService` (the one money path), tx building (QuickTx), pending-tx store, connection config. **No JavaFX / node / HTTP types.** | cardano-client-lib |
| `wallet-node-client` | Yano REST client: CCL suppliers over `/api/v1` (`YanoNodeBackend`) + Yano-specific endpoints (`YanoNodeClient`, `YanoNodePorts`). | `wallet-core`, ccl-backend |
| `wallet-node-launcher` | Managed node: spawn/supervise `yano.jar` as a child process (`ManagedNode`), free-port picking, node locator. | `wallet-core` |
| `wallet-ui` | JavaFX 25 UI (MVVM, no FXML). Screens + `Shell` + dark theme. Depends **only** on the async `WalletUiController` contract — no CCL/node types cross it. | javafx |
| `wallet-app` | Assembly + `main`. `WalletBackendManager` (resolves connection → live backend), `DefaultWalletUiController`, `YanoWalletApp`, and the headless `WalletProbe`. | all of the above |

Dependency direction: `wallet-app → {ui, node-launcher, node-client} →
wallet-core → CCL`. Wallet modules are **not published** (they're in
`nonLibraryModules` in the root `build.gradle`).

## Prerequisites

- JDK 25 (the repo's toolchain; a GraalVM 25 works too).
- The node jar built once (the managed launcher runs it):
  ```bash
  ./gradlew :app:build -x test        # produces app/build/yano.jar
  ```
  The launcher finds it at `app/build/yano.jar` automatically, or set
  `YANO_NODE_JAR=/path/to/yano.jar` (or a native binary).

All commands below are run from the repo root.

## Run the wallet (GUI)

The wallet manages its own node — no separate terminal needed.

### Devnet (fast: instant blocks + a faucet) — recommended for feature testing

```bash
./gradlew :wallet-app:run --args="--node=managed --network=devnet --managed-port=7071"
```

- Data lives in `~/.yano-wallet` (the default — persists across restarts).
  Each network gets its own subdir, so devnet lives under `~/.yano-wallet/devnet`
  and won't collide with a real-network wallet; delete that subdir to reset
  devnet. **Never point `--data-dir` at `/tmp`** — the OS reaps `/tmp` and will
  corrupt the node database (and can lose the vault).
- Opens the window and auto-starts a **devnet node on port 7071** (isolated
  from any real Yano on 7070). First boot ~20s; the sidebar pill flips to
  "synced · block N".
- `--managed-port` pins the REST port so you can hit the faucet (below). Omit
  it and a free port is auto-picked (avoids collisions, but then read the port
  from the Settings screen).
- Devnet regenerates a fresh chain each launch, so faucet funds don't persist
  across restarts (your wallet keys do).

### Preprod / mainnet / preview (real networks)

```bash
./gradlew :wallet-app:run --args="--node=managed --network=preprod --data-dir=~/.yano-wallet"
```

First start on an empty chainstate syncs from the network (hours). To skip
that, seed `<data-dir>/<network>/node/chainstate` with a synced chainstate
before launching — note the node will do a **one-time index backfill** if the
seed lacks the wallet index (address-tx / rewards), which can take ~25 min on
a mainnet-scale chain, then it's at tip and fast on every later launch.

### Connect to an external node instead of managing one

```bash
./gradlew :wallet-app:run --args="--node=external --network=preprod --base-url=http://localhost:7070/api/v1/ --data-dir=~/.yano-wallet"
```

### Interactive (no flags) — pick in the UI

```bash
./gradlew :wallet-app:run
```

First run shows the **Connect** screen: choose the network and "Run a local
node (recommended)" vs "Connect to my node". The choice is saved to
`<data-dir>/connection.json` and **auto-reconnects** on later launches.

## Manual test flow (devnet)

1. **Create wallet** → name + passphrase → write down the 24 words → unlock.
2. **Fund** — Receive tab → copy address → hit the devnet faucet:
   ```bash
   curl -X POST http://localhost:7071/api/v1/devnet/fund \
     -H 'Content-Type: application/json' \
     -d '{"address":"<receive-address>","ada":2000}'
   ```
   Balance updates on the dashboard within seconds.
3. **Send** — Send tab → pick ADA or a native asset → amount → Review & sign →
   Confirm. Watch pending → confirmed in History.
4. **Native tokens** — mint one to exercise the asset picker (see probe below),
   then it shows up in the Send asset dropdown.
5. **Staking** — Staking tab → paste the devnet genesis pool id → Delegate (the
   review shows the ~2 ₳ refundable deposit). Devnet pool:
   `pool1wvqhvyrgwch4jq9aa84hc8q4kzvyq2z3xr6mpafkqmx9wce39zy`.

Closing the window stops the node the wallet started.

## Headless probe (scriptable, same backend as the UI)

The probe drives the same `WalletService` money path as the GUI — useful for
quick verification and for minting test tokens. It talks to an **already
running** node (it does not launch one), so start a node first (e.g.
`cd app && ./start-devnet.sh`, REST on 7070) or point `--base-url` at a
managed node's port.

```bash
D=/tmp/yano-probe
./gradlew -q :wallet-app:probe -PprobeArgs="status --network=devnet --base-url=http://localhost:7070/api/v1/"
./gradlew -q :wallet-app:probe -PprobeArgs="create --name=Alice --network=devnet --data-dir=$D --passphrase=pw"
# fund the printed baseAddress via the faucet, then:
./gradlew -q :wallet-app:probe -PprobeArgs="balance  --wallet-id=<id> --network=devnet --data-dir=$D --passphrase=pw"
./gradlew -q :wallet-app:probe -PprobeArgs="send     --wallet-id=<id> --to=<addr> --ada=10 --wait --network=devnet --data-dir=$D --passphrase=pw"
./gradlew -q :wallet-app:probe -PprobeArgs="mint     --wallet-id=<id> --asset=MYTOKEN --quantity=1000 --wait --network=devnet --data-dir=$D --passphrase=pw"
./gradlew -q :wallet-app:probe -PprobeArgs="delegate --wallet-id=<id> --pool-id=<poolId> --wait --network=devnet --data-dir=$D --passphrase=pw"
./gradlew -q :wallet-app:probe -PprobeArgs="withdraw --wallet-id=<id> --wait --network=devnet --data-dir=$D --passphrase=pw"
```

Probe commands: `status`, `create`, `restore` (`--mnemonic-file`), `list`,
`balance`, `send` (`--message` for CIP-20), `mint`, `delegate`, `withdraw`.
Common options: `--network`, `--base-url`, `--data-dir`, `--passphrase` (or env
`YANO_WALLET_PASSPHRASE`), `--wait`.

## Data layout (`--data-dir`, default `~/.yano-wallet`)

```
<data-dir>/
  connection.json                     # persisted node-connection choice
  <network>/
    wallets/
      index.json                      # public wallet metadata (no secrets)
      <walletId>/vault.json           # Argon2id + AES-GCM encrypted mnemonic
    pending-transactions.json         # local pending/failed tx tracking
    node/
      chainstate/                     # managed node's RocksDB (isolated)
      node.log                        # managed node's stdout/stderr
```

## Build & test

```bash
./gradlew :wallet-core:test :wallet-node-client:test :wallet-node-launcher:test
./gradlew :wallet-app:build
```

## CLI flags (wallet-app main)

| Flag | Meaning |
|---|---|
| `--data-dir=PATH` | Wallet data root (default `~/.yano-wallet`). |
| `--node=managed\|external` | Pre-seed the connection (skips the Connect screen). |
| `--network=devnet\|preview\|preprod\|mainnet` | Network for the pre-seed. |
| `--managed-port=N` | Pin the managed node's REST port (e.g. for the faucet). |
| `--base-url=URL` | External node base URL (`--node=external`). |

Verification-only flags (used by the screenshot/e2e harness):
`--screenshot=PATH`, `--screenshot-delay-ms=N`, `--auto-unlock-wallet-id=…`,
`--auto-passphrase=…`, `--screen=Dashboard|Send|…`,
`--auto-send=addr[,unit,amount]`.

## Architecture notes / gotchas

- **The UI contract is the boundary.** `WalletUiController` (in `wallet-ui`)
  exchanges only immutable records — no CCL/node/HTTP types reach the views.
  `DefaultWalletUiController` (in `wallet-app`) adapts it onto `WalletService`
  on a single-thread backend executor; every result hops back to the FX thread
  via `Platform.runLater`. Confirmation polling runs on its own thread so it
  never blocks the UI or holds the unlocked session.
- **`WalletService` is the one money path** (unlock → draft → submit →
  pending-record → confirm). The probe, the UI, and the future CIP-30 bridge
  all go through it, so behavior can't drift.
- **Managed node = child process** (not in-process): crash isolation and the
  node stays a native-imageable binary. Overrides via `-D` sysprops that MUST
  precede `-jar`: `quarkus.profile=<network>,wallet`, `quarkus.http.port`,
  `yano.server.port`, `yano.storage.path`. The `,wallet` profile is required so
  real networks enable the wallet APIs (address/tx/reward history) — only
  `%devnet` turns them on by itself.
- **Node not native for the UI.** Per ADR-033, the JavaFX UI ships via
  jlink/jpackage (Gluon's GraalVM is frozen below JDK 25); the node stays the
  GraalVM-native binary. Keep `wallet-app`/`wallet-ui` framework-light so a
  native UI stays possible later.
- **JAX-RS routing:** address resources use a `/` class path with absolute
  method paths (a class-level `@Path("addresses")` would shadow the UTXO
  routes).
- **Known limitation:** if the wallet JVM is SIGKILLed, the managed node is
  orphaned and keeps the chainstate lock; the next launch reports "chainstate
  may be locked". A supervisor/watchdog is planned with the M4 packaged app.
