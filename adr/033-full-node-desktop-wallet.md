# ADR-033: Yano Full-Node Desktop Wallet — Architecture, Options, and Delivery Plan

## Status

Proposed

Supersedes the draft `adr/020-yano-wallet-platform.md` on branch `feat/wallet_mvp`
(that number now collides with mainline ADR-020; the design baseline from that
document is carried forward and revised here based on what the MVP
implementation actually proved and disproved).

## Date

2026-07-12

## Context

### Why a full-node wallet

Every mainstream Cardano wallet today depends on third-party infrastructure:

- **Lace** is a browser extension locked to predefined data providers via CSP —
  users cannot point it at their own node.
- **Eternl** allows a custom *submit* endpoint but not a custom chain-data
  backend.
- **Daedalus** is the only full-node wallet ever shipped. Its backend
  (`cardano-wallet`, Haskell) entered maintenance-only mode on 2025-04-01, it
  never had a CIP-30 dApp connector, and its resource profile (24h+ initial
  sync, multi-hour ledger replays on restart, 16 GB RAM) drove its users to
  light wallets. IOG has stated a "lightweight local node service" is planned
  to replace it, but nothing has shipped as of July 2026.

The niche — *a wallet with light-wallet UX that verifies everything against the
user's own node* — is empty. Yano is now in a position to fill it: it syncs
mainnet/preprod/preview/devnet, maintains full ledger state (UTXO, accounts,
stake, rewards, governance, protocol params), validates transactions locally
with Scalus ledger rules, and exposes all of it through a Blockfrost-compatible
REST API that `cardano-client-lib` can consume directly. A Yano wallet is
trustless by construction: no Blockfrost, no Koios, no vendor API keys —
the node the wallet queries is the node the user runs.

### What Yano already provides (branch `feat/full_wallet`, July 2026)

All endpoints below are served by the Quarkus app (`app/`) under the
configurable prefix `${yano.api-prefix:/api/v1}` on port 7070
(`app/src/main/resources/application.yml`), Blockfrost-shaped per ADR-018:

| Area | Endpoints (prefix omitted) | Notes |
|---|---|---|
| UTXOs | `GET /addresses/{addr}/utxos[/{asset}]`, `GET /utxos/{tx}/{ix}`, `GET /credentials/{cred}/utxos` | paginated; by address **and** payment credential |
| Accounts | `GET /accounts/{stake}` (+`/stake`, `/withdrawals`, `/delegations`, `/registrations`, `/mirs`) | history routes need `yano.account-history.enabled=true` |
| Transactions | `POST /tx/submit` (CBOR or hex), `GET /txs/{hash}`, `GET /txs/{hash}/utxos` | submit runs local Scalus ledger-rule validation, returns structured errors on 400 |
| Evaluation | `POST /utils/txs/evaluate` | Ogmios-compatible ex-units result, Scalus-backed, native-image safe |
| Blocks/Epochs | `GET /blocks/{id}`, `/blocks/latest`, `/epochs/latest`, `/epochs/{n}/parameters`, adapot + stake routes | |
| Governance | `GET /governance/proposals...`, `/governance/dreps...` | |
| Network | `GET /network`, `/genesis`, `/status`, `/node/tip` | |
| Scripts | `GET /scripts/{hash}/cbor` | reference scripts only |

The node builds as a GraalVM native image today (CI-proven:
`.github/workflows/release-dist.yml`, per-module `META-INF/native-image`
configs), and the dependency catalog already pins `cardano-client-lib
0.8.0-pre4` (core, address, crypto, quicktx, governance, backend,
backend-blockfrost, cip20) and Yaci 0.5.0-pre11.

### Gaps in the node that a wallet exposes

Identified by inventory of the current API surface:

1. **No address transaction history** — no `/addresses/{addr}/transactions`,
   no full tx list per stake account. Biggest gap; a wallet activity feed
   cannot be built from live UTXOs alone, and restored-wallet address
   discovery (gap-limit scanning) needs a true "was this address ever used"
   answer, not the current "has an unspent UTXO now" proxy.
2. **No mempool/pending-tx visibility** — `GET /txs/{hash}` 404s until the tx
   is in a block; no way to distinguish "pending in mempool" from "unknown".
3. **No L1 event streaming** — SSE exists only for app-chain; wallets must
   poll for new blocks/rollbacks/address activity.
4. **No asset metadata** — no `/assets/{asset}` (CIP-25/68, supply); assets
   appear only inline in UTXO responses.
5. **Reward history not implemented** — `/accounts/{stake}/rewards` missing;
   indexing gated off by default.
6. **No tx metadata endpoint** — `/txs/{hash}/metadata` (CIP-20 messages) absent.
7. **No datum-by-hash endpoint**; script lookup is reference-script CBOR only.
8. **No address summary** — `/addresses/{addr}` aggregate (balance, assets)
   must be computed client-side from paged UTXOs.

These become the node-side workstream (§ Milestone M2). Several are indexing
features already scaffolded but disabled (`yano.account-history.*`,
`yano.filters.utxo`).

### What the previous attempt (`feat/wallet_mvp`) proved

One squashed commit (`dfb8ed4`, 2026-05-01, 106 files / ~16k lines) plus draft
ADR-020. Findings from a full review:

**Keep (proven, high quality):**
- **Module boundaries**: `wallet-core` (pure domain, no UI/node deps),
  `wallet-yano-adapter`, `wallet-bridge`, `wallet-ui`, `wallet-app` — clean
  dependency direction, `wallet-app` the only assembly.
- **The async snapshot contract**: `WalletRuntimeController` +
  immutable `*Snapshot` records fully decouple JavaFX from CCL/node types.
  Best structural idea in the MVP; portable to any UI.
- **Vault crypto shape**: AES-256-GCM, per-write random salt/nonce, atomic
  temp-file+`ATOMIC_MOVE` writes, versioned JSON envelope, `char[]`
  passphrases, buffer zeroization, encrypted-mnemonic-only storage
  (`FileWalletSecretStore`). Reusable nearly verbatim (KDF upgrade below).
- **CCL for all key material**: 24-word BIP-39 via `MnemonicUtil`, CIP-1852
  derivation via `Wallet.createFromMnemonic` — no hand-rolled crypto.
- **Bridge security posture**: loopback bind, Origin validation, per-session
  bearer token, per-origin permissions, deny-by-default approval handler,
  witness-set-only `signTx`.
- **Proof that GluonFX native packaging can work** (a 297 MB
  `yano-wallet` binary was produced) — and proof of what it costs (below).

**Avoid (mistakes to not repeat):**
- **Network hard-coded to preprod** in the controller interface
  (`restorePreprodWallet(...)`), adapter factory, and constants. Network must
  be a first-class runtime parameter.
- **A 2,178-line god-class UI** (`YanoWalletApplication`) with zero tests.
- **"History" tab that only shows pending txs** — the ADR's honest
  SYNCING/INDEXING/READY/PARTIAL state model was designed but never built.
- **`evaluateTx` stubbed** in the adapter — blocks all Plutus dApp flows, even
  though the node has a working Ogmios-compatible evaluate endpoint.
- **In-memory `restoreWallet(mnemonic)` path** that bypasses the vault.
- **Fragile native toolchain**: JavaFX 25 vs Gluon static SDK 21-ea mismatch
  worked around by byte-patching `libglass.a` (JNI version 1.4→1.8) in the
  build. Clever; not shippable.
- **PBKDF2 (210k iters)** — adequate for an MVP, below 2026 best practice for
  an offline-attackable seed file (Argon2id below).

### Technology research findings that constrain the design (verified 2026-07-12)

**JavaFX under GraalVM native-image is not currently viable for this project:**

- Gluon Substrate requires Gluon's own GraalVM builds, frozen at **JDK 17
  (stable, June 2022) / JDK 23-dev (Sept 2024)** — they cannot compile Java 25
  code. Building with Oracle GraalVM 24/25 fails at runtime
  (`UnsatisfiedLinkError: Unsupported JNI version ... required by glass`,
  gluonfx-maven-plugin issue #543, open since Sept 2025). This is exactly the
  failure the MVP's `libglass.a` byte-patch worked around.
- Native builds statically link **JavaFX 21-ea** libraries regardless of the
  JavaFX version on the classpath.
- `javafx.web` is **impossible under native image on Windows** (verified in
  Substrate source: `WindowsTargetConfiguration.java` links no WebKit at all)
  and is a frozen 2023-era WebKit where it does link — a security liability.
- The only current-JDK native path is Liberica NIK 25 "Full with OpenFX"
  (still no `javafx.web`/`javafx.media`), untested for this stack.

**jlink + jpackage is the robust alternative**: JDK 25 + JavaFX 25 LTS
(GA 2025-09-15), all modules, all platforms, no reflection config, 40–120 MB
signed installers, `--mac-sign`/notarization built in. Cold start ~1–3 s vs
milliseconds for native image; a long-running wallet benefits from JIT peak
performance thereafter. GraalVM native remains fully applicable to the **node**
binary, which the wallet bundles.

**CIP-30 for a desktop wallet** (CPS-0010 explicitly names full-node wallets as
needing a novel connector solution):

- The only mechanism compatible with **all existing dApps unmodified** is a
  companion browser extension that injects `window.cardano.{name}` and proxies
  calls to the desktop app over a localhost WebSocket. Proven on Ethereum by
  **Frame** (`ws://127.0.0.1:1248` + extension); no Cardano wallet does this
  today — Yano would be first.
- Bare localhost HTTP + JS shim (the MVP approach) is **deteriorating** for
  public dApps: Chrome 142 (Oct 2025) ships Local Network Access permission
  prompts for public→loopback requests; Safari blocks https→localhost as mixed
  content. It remains fine as a dev-mode connector for locally served dApps.
- **CIP-45** (WebRTC, Eternl) is Active and its JS lib is maintained, but dApp
  adoption is acknowledged as failed, the reference lib depends on a PeerJS
  signaling server, and no Java implementation exists. Optional, later.
- An embedded dApp browser cannot use JavaFX WebView (no WebAssembly — Cardano
  dApps almost universally load WASM libs). A real one means JCEF/JxBrowser
  (~200 MB Chromium). Optional, later.
- A new connector should implement **CIP-30 + CIP-95** (governance, required
  by GovTool), and track CIP-103/104/144.

**cardano-client-lib** (0.8.0-pre4 already pinned; stable 0.7.2): CIP-1852 +
BIP-39 (12/15/18/21/24 words), `Wallet` API with gap-limit-20 UTXO scanning,
QuickTx incl. full Conway governance (`registerDRep`, `createVote`,
`delegateVotingPowerTo`, …), CIP-8/CIP-30 `signData` (COSE), and
`BFBackendService(baseUrl, key)` which works against **any**
Blockfrost-compatible URL — documented against yaci-store and directly
applicable to Yano's API. Crypto is pure Java (native-friendly); the
Retrofit-based backend module needs proxy metadata under native image
(CCL issue #332) — only relevant if the wallet itself is ever native-compiled.

**Key storage**: 2026 best practice for an offline-crackable seed file is
**Argon2id (64 MiB–1 GiB memory, t≥3) + AES-256-GCM**. Bouncy Castle
(`bcprov-jdk18on`, already transitive via CCL) provides `Argon2BytesGenerator`
in pure Java. Every incumbent Cardano format is weaker (Daedalus root key:
PBKDF2 15k iters with a *fixed salt* + unauthenticated ChaCha20; Yoroi
EmIP-003: PBKDF2 19k iters) — treat those as import/export codecs, not
templates. No maintained cross-platform Java keychain library exists; OS
keychain integration is a thin per-OS SPI (DPAPI / macOS Security.framework /
Secret Service) and is a *convenience* layer only — on Windows/Linux keychains
gate by user session, not per app, so the passphrase-encrypted file remains
the root of trust. Hardware wallets: no Java Cardano Ledger lib exists;
Sparrow's Lark proves the JVM pattern (hid4java + APDU) but a Cardano port is
~10k lines; air-gapped QR (Keystone, BC-UR via
`com.sparrowwallet:hummingbird`) is the cheapest first HW integration.

## Goals

1. **Trustless by default**: every balance, reward, protocol parameter, and
   submitted transaction is served/validated by the user's own Yano node.
   No third-party API in the default path.
2. **Light-wallet UX on a full node**: fast app start, honest sync states,
   node lifecycle managed for the user (but never *required* — connecting to
   an already-running Yano node must work).
3. Desktop app for macOS (arm64/x64), Windows x64, Linux x64/aarch64.
4. Full wallet feature set over time: send/receive (ADA + native assets +
   CIP-20 messages), staking, rewards, governance (DRep delegation, voting),
   multi-account, multi-wallet.
5. CIP-30 (+CIP-95) dApp connectivity from the desktop.
6. Distribution users can trust: signed, notarized, reproducible builds from
   a single official domain (the fake "Eternl Desktop" malware campaign of
   Dec 2025 shows desktop wallets are active phishing bait).

## Non-goals (initial releases)

- Mobile, browser-hosted, or web-served wallet UI.
- Hardware-wallet support in the first release (roadmapped, M7).
- Multi-sig coordination, plugin system.
- Implementing key derivation or COSE signing in this repo (CCL owns those).

## Options

### Option set A — wallet ↔ node integration model

| | A1: Embedded in-process node (MVP approach) | A2: Separate node process + local REST | A3: A2 + managed node lifecycle (recommended) |
|---|---|---|---|
| Coupling | UI JVM hosts RocksDB, sync, ledger state | Wallet is a pure API client | Wallet is an API client that can also spawn/supervise the bundled node |
| Node binary | JVM-only (or one giant native image) | Node stays GraalVM-native | Node stays GraalVM-native, shipped inside the installer |
| Chainstate sharing | Exclusive RocksDB lock — wallet and a running node **cannot** share a chainstate | Any number of local clients | Same |
| Failure isolation | UI crash = node crash; node OOM = UI OOM | Independent | Independent; wallet restarts node if it dies |
| Wallet restart cost | Re-open chainstate, possible replay | Instant (node keeps running) | Instant |
| Reuse | Wallet-only | Any Yano (local server, RPi, LAN) | Same, plus zero-config default |
| Effort | Proven in MVP | Thin client via CCL `BFBackendService` | + process supervisor, config/data-dir management |

**Decision: A3.** The REST boundary is the architecture; the lifecycle manager
is UX. Rationale:

- The single-process RocksDB constraint makes A1 actively hostile to power
  users who already run Yano (the wallet would need its own duplicate
  chainstate — 2× disk, 2× sync).
- Daedalus's core lesson is that binding wallet UX to node lifecycle
  (replays on every UI start) kills adoption. A3 keeps the node running
  headless across wallet sessions.
- The node is already proven as a native image; keeping it a separate process
  preserves that (fast start, low memory) without dragging the UI into
  native-image constraints.
- A2 alone (user must run the node themselves) would be acceptable for
  developers but not for the decentralization goal — non-technical users need
  the wallet to install, configure, start, and monitor the node.
- The MVP's embedded runtime (`EmbeddedYanoRuntimeFactory`) is not wasted:
  it remains the right tool for headless tests and could return later as a
  single-binary "portable mode" if ever needed.

Connection modes shipped in the UI:
1. **Managed local node** (default): wallet launches the bundled native
   `yano-node` with a generated config (network, data dir under
   `~/.yano-wallet/<network>/node/`, wallet profile below), supervises it,
   surfaces sync progress from `GET /status`.
2. **External node**: user points the wallet at any Yano base URL
   (localhost or LAN); wallet verifies network magic via `/genesis`.

### Option set B — UI stack

| | B1: JavaFX 25 + jlink/jpackage (recommended) | B2: JavaFX + GraalVM native image | B3: Compose Multiplatform Desktop | B4: Tauri v2 + native Java sidecar | B5: Electron |
|---|---|---|---|---|---|
| Ships today on Java 25 | **Yes** | **No** — Gluon GraalVM frozen at JDK 17/23; issue #543; JavaFX 21-ea static libs | Yes (JVM) | Yes (backend native, UI Rust/TS) | Yes (JS) |
| Native binary | No (bundled trimmed JRE; "native installer" UX) | Yes where it works; `javafx.web` impossible on Windows | **No** — JVM-only despite Kotlin (native desktop not shipped) | Shell native ~3–10 MB + sidecar | No |
| Installer size | 40–120 MB | ~100–300 MB (MVP binary: 297 MB) | 50–150 MB | Smallest shell; sidecar dominates | 80–150+ MB |
| Cold start | ~1–3 s | ms | ~1–3 s | ms (shell) | ~1–2 s |
| Team fit (Java) | Direct | Direct + fragile toolchain | Requires Kotlin adoption | Requires Rust + TS + IPC design | Requires JS/TS |
| Maintenance risk | Low (JavaFX 25 LTS) | High (dependent on Gluon unfreezing; Substrate issue #1363 "was desktop dropped?" unanswered) | Moderate | Moderate (3 webview engines to QA) | High (8-week Chromium treadmill; wallets have shipped Electron RCEs) |

**Decision: B1 now, B2 kept open as a future packaging track.** The user-facing
goal — double-click install, single app bundle, no JRE to install — is met by
jpackage; GraalVM native for the *UI* buys startup milliseconds at the cost of
a toolchain that cannot currently compile our Java version. Design rules that
keep B2 (or a Tauri-sidecar variant B4) possible later:

- `wallet-app` stays Quarkus-free and reflection-light (MVP already did this —
  its bridge deliberately used JDK `HttpServer`).
- No FXML (MVP was already 100% code-built UI; FXML is the main
  reflection/metadata pain under native image); or if FXML is adopted for
  productivity, isolate it so `gluonfx:runagent` tracing stays tractable.
- Native-image metadata kept per-module as the node already does.
- Re-evaluate when Gluon ships a JDK 25 GraalVM or Liberica NIK proves out
  (tracked as a periodic spike, not a blocker).

### Option set C — CIP-30 dApp connector

| | C1: Companion browser extension → localhost WS (flagship) | C2: Loopback HTTP + JS shim (MVP, keep for dev) | C3: Embedded dApp browser (JCEF) | C4: CIP-45 |
|---|---|---|---|---|
| Works with existing dApps unmodified | **All** | Only dApps that load the shim | All, inside the wallet window | Only CIP-45-aware dApps (few) |
| Browser trend | Stable (extensions unaffected by Chrome LNA) | Deteriorating (Chrome 142 LNA prompts; Safari mixed-content) | Stable | Lib maintained; adoption failed; CIP-144 rework in flight |
| Origin authenticity | Extension forwards true page origin | Origin header, spoofable by local processes | Wallet controls the browser | Pairing-based |
| Cost | TS extension + store review + WS server | Already built | ~200 MB Chromium; kills native ambitions for UI process | WebRTC stack in Java (no impl exists) + signaling server |

**Decision: C2 ships first (it exists; dev/test connector for locally served
dApps), C1 is the flagship (phase M5), C3 and C4 optional afterwards.**
C1 security model (Frame/MetaMask-Desktop patterns + geth's DNS-rebinding
lesson): bind WS to `127.0.0.1`, strict `Host`/`Origin` validation, first-use
pairing code typed into the extension, per-origin session tokens and
permissions persisted in the wallet, every `signTx`/`signData` through the
wallet's native approval dialog with a decoded risk summary, witness-set-only
responses. Implement CIP-30 + CIP-95 from the start; `api.signData` via CCL's
`CIP30DataSigner`.

## Architecture

### Module layout (Gradle, on `feat/full_wallet`)

All wallet modules live under a single top-level `wallet/` directory at the
repo root (sibling of `core-api`, `p2p`, `appchain`, …), following the
`appchain/` convention: project names stay flat in `settings.gradle` with
`projectDir` remapped into the folder.

```
wallet/
  wallet-core           Pure domain. Vault, wallet/account model, address
                        derivation (via CCL), balance/history services,
                        tx building (QuickTx), pending-tx tracking.
                        Deps: CCL modules, jackson. No JavaFX/Quarkus/node deps.

  wallet-node-client    Yano REST client. Implements CCL UtxoSupplier /
                        ProtocolParamsSupplier / TransactionProcessor (incl.
                        evaluateTx → POST /utils/txs/evaluate) over the
                        Blockfrost-compatible API + Yano extensions (status,
                        account history, SSE events). Replaces the MVP's
                        wallet-yano-adapter (whose embedded-runtime factory
                        moves to test fixtures).

  wallet-node-launcher  Managed-node lifecycle: locate/spawn/supervise the
                        bundled native yano-node, generate per-network config,
                        health/sync polling, graceful shutdown, log capture.

  wallet-bridge         CIP-30/CIP-95 connector service: session registry,
                        per-origin permissions, approval routing, the WS server
                        for the extension (C1) and the loopback HTTP + JS shim
                        (C2). JDK HTTP/WS only; no frameworks.

  wallet-ui             JavaFX 25. MVVM: one view + view-model per screen,
                        view-models depend only on wallet-core snapshots and a
                        WalletRuntimeController-style async contract (carried
                        over from MVP). No CCL/node types. Testable without
                        a running node.

  wallet-app            Assembly + main. Wires core/client/launcher/bridge/ui,
                        jlink/jpackage packaging, installer metadata, signing.

  wallet-connector-extension   (M5) TypeScript browser extension (MV3), not a
                        Gradle module: injects window.cardano.yano, relays to
                        the bridge WS.

  wallet-cip30-example  Kept from MVP (Mesh-SDK Vite dApp, not a Gradle
                        module) for e2e testing.
```

Build integration (`settings.gradle` / root `build.gradle`):

```groovy
// settings.gradle — flat names, wallet/ directory (appchain pattern)
include 'wallet-core'
project(':wallet-core').projectDir = file('wallet/wallet-core')
// ... same for wallet-node-client, wallet-node-launcher, wallet-bridge,
//     wallet-ui, wallet-app
```

**Wallet modules are not publishable for now**: all of them are added to the
root `nonLibraryModules` set (currently `['app']`), which skips the root
`maven-publish`/`signing`/`java-library` conventions. Each wallet module
applies its own `java-library` (or `application` for `wallet-app`) plugin in
its own `build.gradle`, exactly as `app` does. No wallet artifact is uploaded
to Maven Central until the module boundaries stabilize; if/when some are
promoted to publishable libraries (e.g. `wallet-core`), they simply leave the
set and inherit the standard `yano-` artifact conventions.

Dependency direction: `wallet-app → {ui, bridge, node-launcher, node-client} →
wallet-core → CCL`. The UI never sees CCL or HTTP types; the bridge never sees
keys (it requests signatures through the same controller the UI uses, so every
dApp signature passes the same approval dialog).

**Network is a constructor/runtime parameter everywhere** (enum over mainnet /
preprod / preview / devnet + custom magic), selected at wallet-profile level in
the UI. No network name appears in any interface or method name.

### Key management

- **Storage**: per-wallet vault file (`~/.yano-wallet/wallets/<id>/vault.json`),
  encrypted **mnemonic only** (accounts re-derived on unlock), plaintext
  `index.json` for public metadata — carried over from MVP.
- **Envelope**: versioned JSON header (v2), **Argon2id** (default m=256 MiB,
  t=3, p=2; parameters recorded in the header, floor enforced on read) via
  Bouncy Castle, **AES-256-GCM**, fresh 16-byte salt + 12-byte nonce per
  write, atomic replace. v1 (MVP PBKDF2 vaults) read-supported, rewritten to
  v2 on next passphrase entry.
- **Import/export codecs**: BIP-39 mnemonic (12–24 words); EmIP-003 (Yoroi)
  and cardano-wallet formats as *import* codecs later; CIP-16 serialization
  for key export where applicable.
- **Hygiene**: `char[]` passphrases, zeroize-on-finally, unlock-scoped signing
  sessions with auto-lock timer, signing approval dialog with decoded tx
  summary (outputs, fee, certs, gov actions, metadata), no mnemonic ever in
  clipboard, no `restoreWallet(mnemonic)`-style non-vault path.
- **Phase 2**: OS keychain SPI (DPAPI / Keychain / Secret Service) storing an
  optional *convenience* wrapping key, never the seed itself.
- **Hardware ladder (M7)**: Keystone air-gapped QR (BC-UR, `hummingbird`) →
  Java Ledger Cardano APDU port (Lark pattern, hid4java) → Trezor.

### Node-side wallet profile and new APIs (M2 workstream)

A `%wallet` config profile on the node (documented, used by the launcher):
relay defaults + `yano.account-history.enabled=true` +
`yano.account-history.rewards-enabled=true` + UTXO indexing `both`. Optional
lighter variant using `yano.filters.utxo` scoped to wallet addresses is
possible but conflicts with restore-any-wallet; not the default.

New/changed node endpoints (each Blockfrost-shaped where a Blockfrost
equivalent exists):

1. `GET /addresses/{addr}/transactions` (paginated, block-ordered) and
   `GET /accounts/{stake}/addresses` + per-account tx history. Backing index:
   extend the existing account-history indexer to payment
   credentials/addresses. Also fixes gap-limit discovery (`isUsedAddress`
   becomes truthful).
2. `GET /addresses/{addr}` and `/addresses/{addr}/total` aggregates.
3. **Tx status**: `GET /txs/{hash}/status` → `pending | in_block(block,slot,
   confirmations) | unknown | rejected(reason)`, backed by mempool + chain
   lookup; optionally `GET /mempool` (Blockfrost has an equivalent).
4. **SSE event stream** `GET /events?topics=blocks,rollbacks,txs` (reuse the
   app-chain SSE plumbing for L1): new-block, rollback (with rollback point),
   tx-confirmed. The wallet degrades to polling if the stream is unavailable.
5. `GET /accounts/{stake}/rewards` (reward history; indexer exists, finish +
   enable in wallet profile).
6. `GET /assets/{asset}` (+ `/assets/policy/{policy}`) minimal: supply,
   mint/burn count, CIP-25/CIP-68 metadata resolution.
7. `GET /txs/{hash}/metadata` (CIP-20 et al.).
8. `GET /scripts/{hash}` (type/size) and datum-by-hash if cheaply available
   from existing state; otherwise explicitly out of scope for wallet v1.
9. Fix accepted-but-ignored `order` param on UTXO routes; add total-count or
   cursor metadata for large wallets.

Items 1–5 are required for wallet v1; 6–9 improve it.

### Sync and history UX (the Daedalus lesson, made explicit)

Wallet-visible state machine per wallet profile:

```
NODE_STARTING → NODE_SYNCING(progress%, tip vs wall-clock)
             → WALLET_INDEXING(address discovery / history backfill)
             → READY
   any state → PARTIAL(reason)   e.g. history index disabled on external node
```

- Balances render as soon as UTXO state is queryable, clearly labeled with
  the node's sync point; history fills in as indexing completes. Never a
  single stuck percentage bar.
- **Fast-start option (labeled trust tradeoff)**: Yano's bootstrap mode
  (ADR-025) can start from a recent block with injected state from
  Blockfrost/Koios. Because bootstrap mode disables account-state/history
  subsystems, a bootstrap-mode node yields PARTIAL wallet features and
  imports third-party trust — the UI must say so. Full-sync remains the
  trustless default. (Future: Mithril-style snapshot verification is the
  right long-term answer for fast trustless start; out of scope here.)

### Testing strategy

- `wallet-core`: pure unit tests (vault round-trip incl. v1→v2 migration,
  derivation vectors against CCL, tx building against protocol params
  fixtures).
- `wallet-node-client`: WireMock-style contract tests against recorded Yano
  responses + live tests against devnet profile (existing `devnet-toolkit` /
  testkit; the MVP's embedded runtime factory is reused here as a fixture).
- `wallet-ui`: view-model unit tests (no toolkit needed thanks to MVVM);
  TestFX smoke for critical flows (create → receive → send on devnet).
- `wallet-bridge` + extension: vitest for the shim/extension, e2e via
  `wallet-cip30-example` against devnet.
- e2e: scripted devnet scenario (create wallet → fund via devnet faucet
  endpoint → send → history → delegate → withdraw) runnable in CI.

## Delivery plan

Milestones are sequential but M2 (node APIs) can proceed in parallel with
M1/M3 by different contributors.

**M1 — Core reboot (wallet-core, wallet-node-client)**
Port `wallet-core` from `feat/wallet_mvp` with: network as parameter, Argon2id
vault v2 (+v1 migration), removal of non-vault restore path. Build
`wallet-node-client` on CCL `BFBackendService`/suppliers against a running
Yano; implement `evaluateTx` via `/utils/txs/evaluate`. Headless CLI probe
(carry over `--probe-*` harness) proving create/restore/balance/send on
devnet + preprod. Exit: probe green on devnet and preprod.

**M2 — Node wallet APIs**
Endpoints 1–5 above + `%wallet` profile + SSE. Each lands with
Blockfrost-parity tests where an equivalent exists. Exit: wallet-node-client
switches history/status/rewards from stubs to real endpoints.

**M3 — Desktop UI (JavaFX, MVVM)**
Screens: onboarding (create/restore, passphrase, network select), dashboard
(balance, sync state machine), send (multi-recipient, assets, CIP-20 memo,
fee/draft preview, approval dialog), receive/addresses, history (real, from
M2), staking (delegate, withdraw rewards), settings (node connection modes,
vault, auto-lock). Node lifecycle panel backed by `wallet-node-launcher`.
Exit: full send/receive/delegate cycle on preprod through the UI.

**M4 — Packaging & distribution**
jlink+jpackage installers (dmg/pkg, msi, deb/rpm/AppImage) bundling the
GraalVM-native `yano-node` per platform; macOS signing+notarization, Windows
signing; reproducible-build recipe; single official download domain +
checksums/signatures published. CI matrix (one builder per OS). Exit: signed
installers for all targets from CI.

**M5 — CIP-30/CIP-95 connector**
`wallet-bridge` WS server + pairing; `wallet-connector-extension` (MV3,
Chrome/Brave/Edge first, Firefox after); full CIP-30 method set incl.
`signData`, collateral, plus CIP-95 governance methods; per-origin permission
management UI; e2e against `wallet-cip30-example` and at least two real dApps
(e.g. a DEX + GovTool). Exit: real dApp connect/sign/submit through the
user's own node.

**M6 — Governance & staking depth**
DRep directory + delegation, vote delegation to predefined DReps
(abstain/no-confidence), proposal browser (node governance API), DRep
registration for power users, pool browsing (node has per-pool stake routes;
pool metadata may need an M2-style endpoint addition).

**M7 — Hardware & hardening**
Keystone QR signing; OS-keychain convenience unlock; Ledger Java port spike
(decision gate: port vs bundled `cardano-hw-cli` stopgap); security review +
external audit of vault/bridge before advertising mainnet readiness.

## Consequences

**Positive**
- A wallet whose entire data path is user-owned — the only such offering in
  the ecosystem — built almost entirely from components the project already
  maintains (Yano node, CCL, Yaci).
- Node stays native/small; UI stays on the supported Java toolchain; neither
  blocks the other.
- The CIP-30-over-extension bridge is a first for Cardano and directly
  addresses CPS-0010.

**Negative / accepted costs**
- The wallet UI is not a GraalVM native binary at launch (bundled JRE
  instead). Revisit when the Gluon/Liberica toolchain reaches Java 25.
- Full trustless mode requires a synced node: disk (tens of GB on mainnet)
  and initial sync time. Mitigated by managed lifecycle + honest states +
  optional (clearly labeled) bootstrap mode; not eliminated.
- A browser extension adds a second deliverable with store-review cadence.
- Node gains wallet-driven API surface (M2) that must be maintained with
  Blockfrost-parity semantics.

**Explicitly deferred**
- Embedded single-binary mode (A1) — kept possible via module boundaries.
- CIP-45, embedded JCEF dApp browser, multi-sig, mobile, Mithril-style
  snapshot verification.

## References

- Prior art: branch `feat/wallet_mvp` (commit `dfb8ed4`), draft
  `adr/020-yano-wallet-platform.md` (superseded by this document).
- Yano: ADR-018 (API prefix / Blockfrost compatibility), ADR-025 (bootstrap
  partial state), `app/src/main/resources/application.yml` (profiles,
  subsystem toggles), `app/src/main/java/.../app/api/**` (resources).
- CIPs: CIP-30, CIP-95, CIP-45, CPS-0010, CIP-1852, CIP-8, CIP-16, CIP-20,
  CIP-25/67/68, CIP-103/104/144 (watch list).
- External: Frame (desktop provider + extension pattern), Sparrow
  Wallet/Lark (JVM hardware-wallet + reproducible-build precedent),
  gluonhq/substrate + gluonfx-plugin issue trackers (#543, #536, #544,
  #1363), OWASP password storage / RFC 9106 (Argon2), Chrome Local Network
  Access rollout, cardano-client-lib releases + issue #332.
