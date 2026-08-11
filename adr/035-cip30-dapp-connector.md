# ADR-035: CIP-30 dApp Connector — Companion Extension + Desktop Bridge

## Status

Proposed

Builds on ADR-033 (Full-Node Desktop Wallet) and ADR-034 (Hardware Signer).
ADR-033 named the CIP-30 dApp connector the flagship integration: it lets any
web dApp (in the user's normal browser) transact through Yano, so the user's own
node + own keys sit behind the entire dApp experience — "your keys, your node,
your dApps."

## Date

2026-07-15

## Context

### The problem

CIP-30 is the Cardano dApp↔wallet standard: a dApp calls
`window.cardano.<wallet>.enable()` and then a small API (`getUtxos`, `getBalance`,
`getUsedAddresses`, `signTx`, `signData`, `submitTx`, …). Every Cardano dApp
already codes against it, so supporting CIP-30 makes Yano work with the whole
ecosystem on day one.

But CIP-30 requires injecting `window.cardano.yano` into the page's JavaScript,
and **only a browser extension can do that** in the user's real browser. Yano is
a desktop app (JavaFX + a managed node); its keys and session live in that app,
not in the browser. So the connector is a **thin companion extension** that
injects the CIP-30 API and relays each call to the running desktop app, which
holds the keys, shows an approval, signs, and answers.

### Alternatives considered (and rejected as the primary path)

- **CIP-45 (WebRTC + WebTorrent-tracker relay).** No extension, cross-device.
  Rejected as *first* because dApp-side adoption is thin (few dApps expose a
  CIP-45 connect button), it needs a WebRTC stack on the Java desktop side plus
  tracker/TURN infrastructure, and it buys nothing over CIP-30 for the common
  same-machine case. A worthwhile *fast-follow* for cross-device (phone wallet).
- **Direct localhost from the page** (`https dApp → 127.0.0.1`). Not CIP-30, and
  Chrome's Private/Local Network Access is closing this door.
- **Embedded WebView dApp browser.** No store needed but only for dApps opened
  *inside* Yano; JavaFX WebView is limited and unavailable on the Windows native
  build. Not the "use my wallet from any tab" experience.

### Transport (extension ↔ desktop app)

**Decision (2026-07-16): localhost WebSocket for dev/beta; Native Messaging is
the shipping transport, landed with the release-hardening push (M5).** A shared
pairing token was considered and **rejected**.

- **localhost WebSocket (M1–M2, dev + same-machine):** the extension's
  background service worker connects to `ws://127.0.0.1:<port>` served by the
  desktop app. Simplest to build and debug. The **page never touches localhost**
  — only the extension does — so this is not the page→localhost pattern browsers
  are restricting. Bound to `127.0.0.1` only.
- **Chrome Native Messaging (shipping transport):** Chrome spawns a small native
  host (registered by the installer, whitelisted to the pinned extension ID) that
  bridges stdio ↔ the running app's local IPC. **No listening port at all**, and
  the browser — not us — enforces which extension may connect. Immune to Chrome's
  PNA/LNA tightening. Because the app is long-running and holds the unlocked
  session, the native host is a *thin forwarder* to the app, not the app itself.

**Why Native Messaging over a pairing token.** The bare WebSocket's real weakness
is that `origin` is self-asserted: a local process can claim an already-allowlisted
origin and get **silent reads**, or raise a **spoofed sign prompt** the user
reasonably approves. A shared pairing token would authenticate the client, but the
secret must live on disk (app data dir + the browser's extension storage) — both
readable by any process running as the same user — so it only stops casual attacks
(it is essentially Bitcoin Core's RPC `.cookie` model). It is also throwaway once
Native Messaging lands, and costs a copy-paste setup step. Native Messaging removes
the port entirely and delegates client identity to the browser.

**Honest ceiling:** none of these defeat malware running as the user's own account
(it can read the vault and keylog the passphrase). The hardening matters most for
**hardware-wallet** users, where keys can't be stolen but a spoofed prompt could
still trick a signature. Note the approval model already carries much of the load:
every connect and **every** signature prompts, so a rogue process cannot act
silently — only deceive.

**Precedent:** desktop-app ↔ browser-extension integrations converge on Native
Messaging — 1Password (which additionally verifies code signatures on both ends),
KeePassXC-Browser (native messaging + a user-approved association key), Bitwarden.
Localhost bridges are the discouraged pattern: Trezor Bridge ran one and moved
toward WebUSB. (Cardano's extension wallets — Nami/Eternl/Lace — sidestep this
entirely by holding keys in the extension; Yano is unusual in being a desktop app
with a companion extension, which is exactly why this transport choice matters.)

Sequencing note: Native Messaging pairs naturally with **M5**, because publishing
to the store pins the extension ID — the very thing the native-host manifest must
whitelist — and code-signing lands there too.

### Security model

- The connector only serves when the wallet is **unlocked and connected**.
- `enable(origin)` raises an **approval dialog** in the desktop app naming the
  requesting site; on approval the origin is added to a persisted **allowlist**
  (managed/revocable in a "Connected dApps" screen). `isEnabled()` checks it.
- **Every** `signTx` / `signData` / `submitTx` raises its own approval showing
  what is being signed/sent (decoded summary + raw). Nothing signs silently.
- **Pairing token:** to stop a rogue *local* process from impersonating the
  extension on the localhost socket, the app and extension share a token
  (auto-provisioned over native messaging in M3; a short code shown in the app
  and entered once in the extension for the M1 WS transport). Every request also
  carries the true page `origin`, enforced against the allowlist app-side.
- Watch-only note: only wallets that can sign (software now; hardware in M4)
  serve `signTx`. Read methods work for any unlocked wallet.

### Hardware wallets and `signTx`

CIP-30 `signTx` hands the wallet an already-built transaction (CBOR) and asks
for witnesses. A Ledger cannot sign opaque CBOR — it needs the transaction
re-expressed in its structured signing stream (ADR-034). Translating arbitrary
dApp CBOR (Plutus scripts, many output shapes, certs, mint, …) into Ledger
params is a substantial sub-project, so **M1–M2 target software wallets** (parse
CBOR with CCL, add vkey witnesses for the required signers, zeroize). Hardware
CIP-30 signing is **M4**.

## Decision

A **companion MV3 browser extension** injects `window.cardano.yano` (CIP-30, plus
CIP-95 governance later) and relays each call — over a localhost WebSocket first,
Native Messaging later — to a **desktop bridge** in the wallet app. The bridge
maps CIP-30 methods onto existing wallet/node services (`utxoSupplier`,
`WalletBalanceService`, address services, `transactionProcessor`, and a new
CBOR-witnessing signer), gates connect + every signature behind an approval in
the JavaFX UI, and enforces a per-origin allowlist.

### Project layout

- `wallet/wallet-connector/` — the MV3 extension (plain JS, not a Gradle Java
  module). A Gradle `connectorZip`/packaging task zips it for release; the
  installer/README point users at it. Kept in-repo so the CIP-30 contract, the
  extension, and the desktop bridge version together.
- `wallet/wallet-connector-host/` (or inside `wallet-app`) — the desktop bridge:
  the localhost WS server + CIP-30 request handlers + approval hooks.

### CIP-30 method → wallet mapping

| CIP-30 | Source |
| --- | --- |
| `getNetworkId` | wallet network |
| `getUsed/UnusedAddresses`, `getChangeAddress`, `getRewardAddresses` | address services / watch-only derivation |
| `getUtxos`, `getCollateral` | node `utxoSupplier` (pure-ADA UTxOs for collateral) |
| `getBalance` | `WalletBalanceService` → CBOR `value` |
| `submitTx` | node `transactionProcessor` |
| `signTx(cbor, partial)` | parse (CCL), witness required signers, return witness-set CBOR |
| `signData(addr, payload)` | CIP-8 COSE_Sign1 with the address's key |
| CIP-95 (`getPubDRepKey`, …) | account DRep/stake keys (M4) |

## Milestones

- **CIP30-M1 — pipe + read-only (software).** Extension scaffold (`window.cardano.yano`,
  content bridge, background WS transport); desktop WS bridge; `enable` approval
  + origin allowlist; `getNetworkId`, addresses, `getUtxos`, `getBalance`,
  `getCollateral`. Verify with a real dApp's connect + balance read.
- **CIP30-M2 — signing (software).** `signTx` (CBOR witnessing + approval showing
  a decoded summary), `signData`, `submitTx`. Verify an end-to-end dApp swap/mint
  on preprod.
- **CIP30-M3 — hardening.** "Connected dApps" management screen (revoke);
  reconnect/lifecycle; multi-tab. (Transport hardening moved to M5 — see the
  transport decision above; the pairing token is rejected.)
- **CIP30-M4 — governance + hardware (incl. Plutus).** CIP-95 methods; Ledger
  `signTx` via a CBOR→device-params translator (reusing ADR-034's stream).
  **Plutus script transactions are in scope**: the Cardano app has a dedicated
  `PLUTUS_TRANSACTION` signing mode (wire value 7) whose only stated restriction
  is "must not contain a pool registration certificate" — collateral inputs,
  required signers, script data hash, mint, reference inputs and output
  datums/reference scripts are all supported. This matters because **most real
  dApp transactions are script transactions**; a non-Plutus-only signer would be
  of little use.
  The genuine difficulty is not the device's feature set but **exact CBOR
  reproduction**: the device recomputes the tx hash from the structured stream, so
  every body field and encoding knob (set tags, per-output map-vs-array format,
  datum/reference-script presence, asset ordering) must reproduce the dApp's bytes
  precisely. The tx-hash gate makes any mismatch abort safely rather than mis-sign.
  Practical limits remain device-side: tx size/item ceilings, and script outputs
  are shown with limited detail.
  Sub-steps: **M4a** full-body signing stream + signing mode (protocol layer,
  unit-tested against the ledgerjs layouts); **M4b** the CBOR→params translator
  (detect knobs from the dApp's own CBOR, pick ORDINARY vs PLUTUS mode, derive
  witness paths); **M4c** wire into the CIP-30 hardware path + device-test a simple
  send, then a real script tx.
- **CIP30-M5 — distribution + transport hardening.** Chrome/Firefox/Edge listings
  (pinned key → stable ID), privacy policy, unlisted beta first — and, using that
  now-pinned ID, the **Native Messaging** host + manifest (installer-registered),
  retiring the localhost WebSocket for released builds.

  **M5 transport DONE (2026-07-19, `feat/cip30_native_messaging`).** The
  KeePassXC-proxy shape: Chrome launches a zero-dep relay
  (`wallet-connector-proxy` jar) that pipes its stdio to the wallet's unix
  domain socket (`~/.yano/cip30.sock`, owner-only perms). Chrome's framing
  (4-byte LE length + JSON) is used on BOTH legs, so the proxy never parses a
  message — it is a raw byte relay. `Cip30LocalSocketServer` (connector-host)
  serves the socket with the same `Cip30Rpc` envelope as the WebSocket server.
  The extension's manifest pins a public key → stable ID
  `bjnkcmbkjaebecgllkgbeapbjcknnedn` (private key kept out of git); its
  background worker prefers `connectNative` and falls back to the WS while the
  host isn't installed. The wallet's Settings gains "Install browser connector":
  writes the proxy jar, a launcher pinned to the app's own Java runtime, and
  host manifests for Chrome/Chromium/Edge/Brave (macOS + Linux; Windows needs
  the registry — deferred to installer work). Proven end-to-end in tests by
  launching the real proxy jar as a subprocess. Remaining in M5: store
  listings, privacy policy, retiring the WS default, Windows registry.

## Consequences

- Yano becomes usable with the existing dApp ecosystem without dApp changes.
- New surface to secure: the localhost/native bridge and the approval flows are
  now part of the trust boundary — origin allowlist, per-signature approval, and
  a pairing token are load-bearing, not optional.
- A JS extension now lives beside the Java wallet; its CIP-30 contract must stay
  in lockstep with the desktop bridge (kept in one repo for that reason).
- Store review + code-signing (per the release-hardening track) gate the public
  M5; M1–M3 run as an unpacked/unlisted extension.
