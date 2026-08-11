# ADR-034: Hardware Wallet Support — Pure-Java Device Signer

## Status

Proposed

Builds on ADR-033 (Yano Full-Node Desktop Wallet). ADR-033 delivered a wallet
whose *data* is trustless (the node the wallet queries is the node the user
runs); this ADR adds trustless *key custody* by letting the wallet sign with an
external hardware device (Ledger first), so the seed never exists on the host.
The two are complementary — together they are the strongest form of
"your keys, your node, nothing in between."

## Date

2026-07-13

## Context

### Why hardware wallet support

ADR-033's custody model is a software seed: a 24-word BIP-39 mnemonic encrypted
at rest in the Argon2id + AES-256-GCM vault (`FileWalletSecretStore`), decrypted
into a CCL `Account`/hd-wallet on unlock, used to sign locally
(`SignerProviders.signerFrom(account)`), zeroized after. That is a good hot
wallet, but the private key still materializes in host RAM at signing time.

A hardware wallet removes that last exposure: the seed lives on a dedicated
secure element, the host only ever holds the **extended public key** (xpub), and
signing is a request the device fulfils after the user confirms the transaction
**on the device screen**. For a wallet whose whole thesis is "verify everything
yourself, trust no third party," device custody is the natural next axis of
trust minimization. It is also table stakes for anyone holding meaningful value.

### What changes, and what does not

Hardware support touches exactly two steps of the ADR-033 money path
(unlock → draft → sign → submit → confirm):

- **Account setup** becomes a *watch-only xpub import*. Connect the device once,
  read the account-level extended public key (CIP-1852 path
  `1852'/1815'/account'`), persist only that. All address derivation, balance,
  history, and receive flows then work **fully offline**, device unplugged —
  they never needed the private key.
- **Signing** becomes a *device round-trip*. Instead of a local CCL signer, the
  drafted transaction is serialized into the Ledger Cardano app's wire format,
  streamed to the device, reviewed and approved on-device, and the returned
  witnesses (ed25519 signatures) are attached to the CCL `Transaction` witness
  set before submission through the user's node.

Everything else — drafting via QuickTx, coin selection, submission via the node,
confirmation tracking, the whole UI — is unchanged. This is deliberately a new
*signer* behind the existing `WalletService` facade, not a new wallet.

### Research findings that constrain the design (verified 2026-07-13)

1. **CCL has device *derivation* parity but no device *transport*.** The
   `*-ledger` modules in the CCL tree (`cardano-client-ledger`, and Yano's
   `yano-ledger-rules`, `yano-ledger-state`, `plutus-ledger-api`) are *Cardano
   ledger rules/state* — none is a device library, and there is no
   `hid`/`usb`/`ledger-device` module. **However**, CCL PR #542 (*"Implement
   LEDGER and TREZOR derivation,"* merged 2025-10-28, and **already present in
   our pinned `cardano-client-crypto-0.8.0-pre4`**) adds a `Bip32Type` enum
   `{ICARUS, LEDGER, TREZOR}` and `CIP1852`/`HdKeyGenerator` overloads that
   reproduce, *in software*, the CIP-0003 master-key derivation each vendor uses
   (ICARUS default; LEDGER = PBKDF2-HMAC-SHA512 ×2048; TREZOR = ×1). That is
   derivation-scheme parity — it does **not** talk to a device, stream a tx, or
   return device witnesses. So we still own the USB/APDU transport and the
   Cardano-app signing protocol; PR #542 only helps the derivation math (see the
   next finding and "Related but distinct" below).

2. **The canonical Ledger-Cardano protocol library is JavaScript**
   (`@cardano-foundation/ledgerjs-hw-app-cardano` +
   `@ledgerhq/hw-transport-node-hid`). It is transport-agnostic protocol logic
   (serialize tx → APDU frames, parse witnesses) plus a **native Node addon**
   (`node-hid`, C++/N-API) for the USB I/O. The addon cannot run under GraalVM
   polyglot, and GraalVM's bundled Node.js runtime is being phased out, so
   "embed the whole npm package" is not a viable path.

3. **The closest architectural precedent — Sparrow (a JavaFX Bitcoin wallet) —
   does device I/O in pure Java, and deliberately migrated *away* from a
   subprocess.** Its `Hwi` class now delegates to `com.sparrowwallet.lark.Lark`,
   a pure-Java library described as "a port of the Python library HWI,"
   supporting Coldcard/Trezor/Ledger/BitBox02/Jade/Keepkey/OneKey over USB with
   no Python and no bundled interpreter. Its transport stack is
   `hid4java` (HID via JNA), `usb4java` (libusb), and `jSerialComm` (serial for
   Jade). Sparrow also supports NFC smartcards via the JDK's built-in
   `javax.smartcardio`, and fully **airgapped** signing via PSBT files + animated
   QR (UR) with no device driver at all.

4. **Lark is not reusable for Cardano signing, but its *shape* is the blueprint.**
   A Ledger runs separate device apps — the Bitcoin app and the Cardano app each
   define their own APDU instruction set. Lark speaks the Bitcoin app
   (`lark.ledger.command.BitcoinInsType`); the Cardano app's serialization/INS
   codes are different (that is exactly what `ledgerjs-hw-app-cardano`
   implements). What *is* app-agnostic and reusable in shape is the layer below:
   HID framing / `APDUCommand`/`APDUResponse` over a `hid4java` transport. So the
   Cardano work is "Lark's `ledger/` package, retargeted to the Cardano app."

The conclusion the research forces: for a JavaFX-on-stock-JDK wallet, a
**pure-Java device signer** is the proven, in-process, no-sidecar path. The
GraalJS-polyglot option (embed `ledgerjs` protocol logic, keep transport in
Java) would only save the protocol port, at the cost of JS bundling, a
Promise/microtask bridge, and a ~tens-of-MB JS engine — a poor trade versus the
Sparrow-validated native route.

### Related but distinct: importing a hardware seed phrase in software

There are two different "hardware wallet" capabilities, and this ADR is about the
first:

- **(a) Connect a physical device (this ADR).** The seed never leaves the secure
  element. The device supplies the account xpub; signing happens on-device.
  `Bip32Type` is **not** needed here — the xpub the device returns already
  encodes its derivation scheme, and we derive child address keys from it with
  CCL's existing `CIP1852.getPublicKeyFromAccountPubKey(accountXpub, role,
  index)` (no private key involved).
- **(b) Restore a Ledger/Trezor *recovery phrase* into a software (hot) wallet.**
  A user types their 24 words into Yano to reach funds without the device (lost
  device, quick access). This is **security-degraded** — the seed is now on the
  host — so it is a clearly-labelled *recovery/convenience* path, not device
  custody. Its one correctness requirement is derivation-scheme match: derive
  with `Bip32Type.LEDGER`/`TREZOR` or the restored addresses won't match the
  device. PR #542 is exactly what makes (b) correct, and it is cheap (already in
  our CCL). Whether to expose (b) is an ADR-033 soft-wallet decision; we note it
  here because the same finding also flags a latent bug: **ADR-033 restore today
  defaults to ICARUS**, so importing a Ledger/Trezor-origin seed silently derives
  the wrong addresses — restore should offer a derivation-scheme choice.

## Goals

- Sign real Cardano transactions (payment, delegation, withdrawal, native-asset)
  with a Ledger device whose seed never touches the host, submitted through the
  user's own Yano node.
- Watch-only accounts: derive addresses, show balance/history/receive with the
  device disconnected.
- On-device verification of receive addresses (defeat address-swap malware).
- A device-signer abstraction that fits behind the existing `WalletService`
  money path and composes with the future CIP-30 connector (a dApp cannot tell
  whether the backing account is soft or hardware).
- Stay pure-JVM and in-process: no Python, no Node, no separate signing daemon.
- Keep all device/native code out of the node — it lives only in the wallet UI
  process, so the node remains GraalVM-native-imageable.

## Non-goals (initial releases)

- Multisig / shared-wallet hardware flows.
- Plutus script witnessing, pool-registration-as-operator, Catalyst voting
  registration on device.
- Trezor, BitBox, Keystone/airgapped, and NFC cards in the first release
  (designed for, sequenced later — see Delivery plan).
- Bluetooth (Ledger Nano X BLE) — USB-HID only initially.
- Bundling a device firmware updater; users update via vendor tooling.

## Options

### Option set A — protocol integration approach (the core decision)

- **A1 — Native Java APDU (chosen).** Implement the Ledger Cardano app's command
  set (getExtendedPublicKey, deriveAddress, signTransaction, signMessage) in
  Java on top of a `hid4java` HID transport, mirroring the structure of Lark's
  `ledger/` package. Pure JVM, in-process, no extra runtime. Cost: we own a
  security-sensitive protocol port and must track Ledger Cardano app versions.
- **A2 — Node/HWI-style subprocess.** Bundle a JS (or Python) helper using the
  official `ledgerjs`/HWI and talk to it over localhost. Lowest protocol risk
  (canonical lib), but bundles and code-signs a whole second runtime per OS, adds
  IPC, and complicates jpackage. Rejected: Sparrow explicitly moved off this.
- **A3 — GraalJS polyglot.** Run `ledgerjs-hw-app-cardano` protocol logic in an
  embedded GraalJS `Context` (library on stock JDK), transport in Java. Reuses
  the canonical serialization without a sidecar, but adds JS bundling + Node-shim
  (Buffer), a Promise/microtask pump, a single-thread context constraint, and a
  large engine dependency. Rejected as the lead: more moving parts than A1 for a
  bounded protocol, and no production precedent in this class of app.

**Decision: A1.** It matches the proven Sparrow direction, keeps the whole wallet
one pure-JVM process, and the Cardano app protocol is well-documented and
finite. A3 remains a fallback if the protocol port proves heavier than expected.

### Option set B — device coverage and sequencing

- **B1 — Ledger over USB-HID first (chosen first target).** Largest Cardano
  hardware install base; app protocol is documented; testable in CI via the
  **Speculos** emulator without physical hardware.
- **B2 — Trezor (Model T / Safe) second.** Cardano-capable, protobuf transport
  over USB — reuses the module and watch-only model, different wire codec.
- **B3 — Airgapped QR (Keystone / UR) as an alternative track.** No driver, no
  USB permissions; Keystone supports Cardano via UR-encoded frames. Cheapest to
  ship in terms of native/OS risk and a strong "no cable ever" story; sequenced
  as an independent milestone that can leapfrog B2 if prioritized.

**Decision: B1 now, design the SPI so B2/B3 are additive.**

### Option set C — transport library

- **C1 — `hid4java` (JNA) for Ledger/Trezor HID (chosen).** Same library Sparrow
  ships; JNA host access (`--enable-native-access`), bundled native `hidapi` per
  OS via jpackage. Fine for a stock-JDK JavaFX app (we are not native-imaging the
  UI — ADR-033).
- **C2 — `usb4java` (libusb).** Needed only for bulk/interrupt devices outside
  the HID class; kept in reserve, not required for Ledger.
- **C3 — `javax.smartcardio` (JDK built-in).** For any future NFC-card device;
  no third-party native lib. Out of scope now.

**Decision: C1.**

## Architecture

### New module: `wallet/wallet-hardware`

A new non-publishable module (added to `nonLibraryModules`) that isolates all
JNA/native/device code from the rest of the wallet:

```
wallet/
  wallet-core/       ← defines the signer SPI + watch-only keystore model
  wallet-hardware/   ← NEW: hid4java transport + Ledger Cardano app protocol
  wallet-node-client/
  wallet-node-launcher/
  wallet-ui/
  wallet-app/         ← wires a DeviceSigner impl into WalletService
```

`wallet-core` gains a small SPI (`WalletSigner` with a soft implementation and a
`DeviceSigner` contract) and a keystore model that distinguishes a **seed
keystore** (existing vault path) from a **device keystore** (xpub + device
model + account index, no secret). `wallet-hardware` depends on `hid4java` and
CCL transaction-spec only; it never sees the vault. `wallet-app` (the only
assembly) selects the signer based on the active wallet's keystore type.

### Custody model: watch-only device keystore

```
DeviceKeystore(
  walletModel        // LEDGER_NANO_S_PLUS, LEDGER_NANO_X, ...
  accountIndex       // CIP-1852 account'
  accountXpub        // extended public key at 1852'/1815'/account'
  stakeXpub          // for the reward/stake credential
)  // no seed, no vault entry
```

Address derivation, gap-limit scanning, balance, and history run off the xpub
exactly as the soft wallet does — child address keys come from CCL's existing
`CIP1852.getPublicKeyFromAccountPubKey(accountXpub, role, index)`, so no new
derivation code is needed and no `Bip32Type` selection applies (the device's
xpub already encodes its scheme). The existing `WalletService.Session` queries
are unchanged. Only `submit`-time signing dispatches to the device.

### Ledger Cardano app protocol surface (in `wallet-hardware`)

Mirrors Lark's `ledger/` structure, retargeted to the Cardano app:

- `LedgerTransport` — `hid4java` HID device open/close, APDU frame
  chunking/reassembly (`APDUCommand`/`APDUResponse`). App-agnostic; the reusable
  layer.
- `LedgerCardanoApp` — the Cardano INS set:
  - `getVersion` / `getSerial` — app presence + version gating.
  - `getExtendedPublicKey(path)` — account import + stake xpub.
  - `deriveAddress(spec, display)` — on-device receive-address verification.
  - `signTransaction(...)` — the streaming protocol: init → inputs → outputs
    (incl. multi-asset) → fee/ttl → certificates (registration, delegation) →
    withdrawals → optional metadata/aux-data hash → confirm → collect witnesses
    per requested path.
  - `signMessage(...)` — CIP-8, for the future CIP-30 `signData`.
- `LedgerCardanoSigner` implements the `wallet-core` `DeviceSigner`: takes the
  CCL draft, walks its `TransactionBody`, streams it, and returns
  `VkeyWitness`es the money path attaches to the CCL `Transaction`.

### Signing flow (contrast with the soft path)

```
draft (QuickTx)  ──►  DeviceSigner.sign(draft, deviceKeystore)
                        ├─ open hid4java device, assert Cardano app + version
                        ├─ serialize TransactionBody → Ledger Cardano APDU stream
                        ├─ device shows amounts/fee/dest; USER APPROVES on-device
                        ├─ receive witnesses (ed25519 sigs) for signing paths
                        └─ attach VkeyWitnesses to CCL Transaction
                     ──►  submit via node (unchanged)  ──►  confirm (unchanged)
```

No `Session` decrypt, no vault touch, no key in host RAM. A dedicated
single-threaded device executor owns the connection (the transport is not
thread-safe), matching the confirmation-tracker daemon pattern already in
`WalletService`.

### UI (in `wallet-ui`)

- **Add-wallet → "Connect hardware wallet"**: enumerate devices, verify the
  Cardano app is open, import xpub, name the account.
- **Send/Stake review**: after "Confirm & submit," a device-prompt state
  ("Review on your Ledger…") replaces the passphrase prompt; success/cancel/
  timeout are surfaced as toasts. Reuses the existing draft→review→submit screens.
- **Receive**: a "Verify on device" action calls `deriveAddress(display=true)`
  so the user confirms the address on the device screen before sharing it.
- No auto-refresh or vault code paths change.

### Packaging and platform

- Native code (JNA `hidapi`) lives only in the wallet UI JVM; jpackage bundles
  the per-OS native libs. The **node stays GraalVM-native** — unaffected.
- Linux needs udev rules for non-root HID access (document + ship a rules file);
  macOS/Windows work without extra permissions for HID-class devices.
- `--enable-native-access=ALL-UNNAMED` added to the wallet launch args.

## Delivery plan

Milestones are independently shippable; each ends with a device (or Speculos)
end-to-end check on preprod.

- **HW-M1 — SPI + transport.** `WalletSigner`/`DeviceSigner` SPI and device
  keystore in `wallet-core`; `wallet-hardware` scaffold; `hid4java` transport;
  Ledger enumeration + `getVersion`. Exit: detect a connected Ledger and read its
  Cardano app version.
- **HW-M2 — Watch-only account.** `getExtendedPublicKey` → import xpub; address
  derivation/balance/history off the device; `deriveAddress` on-device receive
  verification. Exit: create a hardware account, see its balance and a verified
  receive address, device unplugged afterward.
- **HW-M3 — Payment signing.** `signTransaction` streaming for a simple
  ADA payment; attach witnesses; submit via node. Exit: preprod ADA send signed
  on device, confirmed on-chain.
- **HW-M4 — Certs + assets + multi-account.** Delegation, withdrawal,
  native-asset outputs; multiple account indices. Exit: delegate and withdraw
  rewards from a hardware account on preprod.
- **HW-M5 — Second channel.** Either Trezor (protobuf) or airgapped Keystone/UR,
  proving the SPI generalizes.
- **HW-M6 — Hardening + packaging.** Speculos-based CI conformance vectors
  (cross-checked against `ledgerjs` test vectors), Ledger-app version pinning +
  graceful "unsupported app version" handling, jpackage native-lib bundling,
  udev rules, security review, developer + user docs.

## Consequences

**Positive**

- Strongest available trust model for a Cardano wallet: trustless data (own
  node) + trustless custody (device), one pure-JVM process, no sidecar.
- Aligns with the proven Sparrow architecture; the transport layer is reusable
  across Ledger/Trezor and future devices.
- Watch-only accounts are useful on their own (view-only wallets) and fall out
  of the same work.
- Composes with CIP-30: dApp signing "just works" against a hardware account.

**Negative / costs**

- We own a security-sensitive protocol implementation and must track Ledger
  Cardano app releases (INS/serialization changes, new constraints).
- Per-OS native HID libraries to bundle and code-sign; Linux udev friction;
  device testing needs physical hardware or the Speculos emulator.
- USB-HID transport is finicky (device busy, app-not-open, cable/hub issues) —
  needs careful error surfacing, already partly modeled by Lark's exception set.

**Risks and mitigations**

- *Protocol correctness* → port against, and CI against, the `ledgerjs` Cardano
  test vectors; run Speculos in CI so signing is exercised without hardware.
- *App-version drift* → version-gate at connect; refuse with a clear message
  rather than mis-serialize.
- *Scope creep* → strict non-goals (no multisig/Plutus/pool-op initially); the
  SPI is designed so those are additive, not blocking.

## References

- ADR-033 — Yano Full-Node Desktop Wallet (custody model, module layout,
  `WalletService` money path, JavaFX/jpackage decision).
- Sparrow Wallet — `github.com/sparrowwallet/sparrow` (`io/Hwi.java`,
  `io/Device.java`, `io/ckcard/*`, airgapped `Coldcard/Passport/Keystone`).
- Lark — `github.com/sparrowwallet/lark` (pure-Java HWI port; `ledger/`,
  `bitbox02/`, `trezor/`; deps `hid4java`, `usb4java`, `jSerialComm`).
- `@cardano-foundation/ledgerjs-hw-app-cardano` — Cardano Ledger app protocol
  (serialization + test vectors to conform against).
- CCL PR #542 — *"Implement LEDGER and TREZOR derivation"* (merged 2025-10-28,
  in `0.8.0-pre4`): `Bip32Type` {ICARUS, LEDGER, TREZOR}, CIP-0003 master-key
  derivation parity — `github.com/bloxbean/cardano-client-lib/pull/542`.
- Ledger Cardano app spec (APDU/INS set) and **Speculos** device emulator.
- CIP-1852 (HD derivation), CIP-8 (message signing), CIP-30 (dApp connector).
- `hid4java` (JNA HID) — the chosen transport library.
