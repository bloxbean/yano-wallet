# ADR-034 Hardware Wallet — Progress Tracker

Branch: `feat/full_wallet_ledger` (cut from `feat/full_wallet`). Design in
[`034-wallet-hardware-signer.md`](034-wallet-hardware-signer.md).

## Status summary

| Milestone | State | Exit criterion |
|---|---|---|
| HW-M1 SPI + transport | **DONE — verified on hardware** | detect a connected Ledger and read its Cardano app version |
| HW-M2 watch-only account | **DONE — verified on hardware** | import xpub; balances/history/receive off the device |
| HW-M3 payment signing | **DONE — signed on device, confirmed on-chain** | preprod ADA send signed on device, confirmed |
| HW-M4 certs + assets + multi-account | not started | delegate + withdraw from a hardware account |
| HW-M5 second channel | not started | Trezor or airgapped-QR proves the SPI generalizes |
| HW-M6 hardening + packaging | not started | Speculos CI, version gating, jpackage natives, udev, docs |

## HW-M1 — SPI + transport (2026-07-13)

**Delivered:**
- **Module**: new non-publishable `wallet/wallet-hardware` (in `settings.gradle`,
  `nonLibraryModules`, `projectDir` remap). Depends on `wallet-core` (SPI) +
  `org.hid4java:hid4java:0.8.0` (catalog `libs.hid4java`) + slf4j. Device/native
  code is confined to this module and the wallet UI JVM — never the node.
- **wallet-core SPI + model** (additive; `WalletService` untouched):
  `hardware/HardwareWalletService` (SPI: `enumerate`, `getCardanoAppVersion`),
  `DeviceType` {LEDGER}, `HardwareDevice` (identity only — no hid4java type
  crosses the boundary), `DeviceVersion`, `DeviceKeystore` (watch-only xpub
  model, populated in HW-M2), `HardwareWalletException`.
- **wallet-hardware Ledger impl**:
  - `ledger/LedgerHidFraming` — Ledger's APDU-over-HID framing (channel + tag
    0x05 + seq; report 0 carries the 2-byte total length), library-independent
    and unit-tested.
  - `ApduCommand`/`ApduResponse` — short-form APDU serialize + SW split.
  - `LedgerTransport` — hid4java write/read + reassembly over an opened device.
  - `LedgerCardanoApp` — CLA 0xD7; `getVersion` (INS 0x00); INS map for later
    milestones; status-word → actionable message.
  - `LedgerHardwareWalletService` — enumerate Ledger vendor 0x2C97, prefer the
    APDU interface (usage page 0xFFA0) with a fallback to all Ledger interfaces
    when the usage page is unavailable (Linux without udev); open-by-path.
- **Probe**: `wallet-app` `LedgerProbe` + `./gradlew :wallet-app:ledgerProbe`
  (with `--enable-native-access=ALL-UNNAMED`).

**Verified:**
- `wallet-hardware` unit tests green (8 tests: APDU serialize, single/multi-report
  framing across boundaries, wrap↔reassemble round-trip for sizes 0..1024, SW
  split, wrong-channel rejection).
- `ledgerProbe` runs on macOS with no device: native `hidapi` loads, enumeration
  returns empty and reports it cleanly (no crash, no JNA native-access error).

**Hardware-verified (2026-07-13):** `./gradlew :wallet-app:ledgerProbe` against a
physical **Ledger Nano X** — enumerated the device (`Ledger Nano X`,
`path=DevSrvsID:4295154172`) and the `getVersion` round-trip returned Cardano app
**7.2.1**. The full framing/transport/APDU stack is proven end-to-end on real
hardware, not just unit-tested. (App 7.2.1 informs the version gate in HW-M6.)

**Notes / decisions:**
- Fixed HID channel `0x0101` (device echoes it; matches ledgerblue).
- Used upstream `org.hid4java:hid4java:0.8.0` (Sparrow's `com.sparrowwallet` fork
  is not on Maven Central). API confirmed against the 0.8.0 source.
- The Linux usage-page fallback is best-effort; udev rules land in HW-M6.

## HW-M2 — watch-only account (2026-07-13)

**Delivered:**
- `LedgerBip32` — CIP-1852 constants (purpose 1852', coin 1815') + the APDU path
  encoding (`[len][elem×4 BE]`, hardening pre-applied), matching `ledgerjs`
  `path_to_buf`. Only the account level `1852'/1815'/account'` is requested.
- `LedgerCardanoApp.getExtendedPublicKey(path)` — v7 single-key protocol
  (INS 0x10, P1 0x00, P2 0x00, data = path), returns the raw 64-byte
  pubkey‖chaincode CCL consumes directly.
- SPI `HardwareWalletService.importAccount(device, accountIndex)` →
  `LedgerHardwareWalletService` reads the account xpub and returns a watch-only
  `DeviceKeystore`.
- `DeviceAddressService` (wallet-core) — derives receive/change/stake addresses
  from a `DeviceKeystore` via CCL `CIP1852.getPublicKeyFromAccountPubKey`
  (non-hardened role/index child derivation) + `AddressProvider`. Works with the
  device disconnected.
- `ledgerProbe` extended: imports account 0 and prints the account xpub + first
  mainnet/preprod receive address + stake address, to compare against Ledger Live.

**The derivation-correctness cross-check (the user's concern).** The historical
Cardano-on-Ledger pitfall is the **CIP-0003 master-key scheme**: a Ledger derives
its root key with the LEDGER scheme (not Icarus), so software must use
`Bip32Type.LEDGER` to reproduce a Ledger's keys. Our hardware path sidesteps this
entirely — the account key comes *from the device*, already scheme-correct, and we
only do standard non-hardened child derivation below it. Verified against the
canonical `ledgerjs-hw-app-cardano` vectors (Speculos seed
`abandon…about`) in `LedgerDerivationVectorTest` (4 tests, all green):
- `getPublicKeyFromAccountPubKey(ledgerAccountXpub, 0, 1)` == ledgerjs
  `b3d5f4…`; `(…, 2, 0)` == `66610e…` (exact key match).
- `DeviceAddressService` receive address (networkId 0) ==
  `addr_test1qpd9x…9nnhk4` (exact address match).
- ICARUS scheme yields *different* keys — documents the quirk. (Relevant only to a
  future soft-restore of a Ledger seed, per ADR-033; not to hardware.)
- Caveat: ledgerjs's mainnet-HRP base fixture uses `Networks.Fake` (a
  non-standard networkId), so the real mainnet cross-check rides on the shared
  key hashes + networkId-0 vector; the network nibble is the only difference.

**Hardware-verified (2026-07-13):** `ledgerProbe` on the Nano X imported account 0
and the printed receive address **matched another wallet's address for the same
device exactly** — the full watch-only path (device xpub → CCL child derivation →
address) is confirmed correct on real hardware.

**On-device address verification (2026-07-13):** INS 0x11 split into
`LedgerCardanoApp.deriveAddressBytes` (P1 0x01, returns bytes) and
`displayAddress` (P1 0x02, shows on screen, **returns no data** — success = user
approved). Gotcha found on hardware: the display variant returns an empty body,
so parsing it as an address threw `AIOOBE`; `showReceiveAddress` now reads bytes
via P1 0x01 first, then displays via P1 0x02 (one approval prompt). Plus
`serializeBaseAddressParams` (`type|networkId|spendingPath|0x22|stakingPath`,
unit-tested against a hand-computed vector) and `LedgerBip32.paymentPath`/
`stakePath` (full 5-element paths). Declined status words 0x6985/0x5001 mapped.
**Hardware-verified (2026-07-13):** `ledgerProbe` on the Nano X displayed the
receive address on-screen; after approval it printed `on-device verification:
MATCH ✓` (device-derived address == software-derived). HW-M2 fully done on
hardware.

## HW-M3 — transaction signing, first cut (2026-07-13)

**Scope:** ordinary ADA-only payment, third-party address bytes for all outputs
(incl. change). No certs/withdrawals/tokens/aux-data/mint yet.

**Delivered:**
- `LedgerCardanoApp.signTransaction(...)` — the INS 0x21 streaming flow: INIT
  (0x01) → INPUTS (0x02) → OUTPUTS (0x03, P2 basic-data 0x30 then confirm 0x33)
  → FEE (0x04) → TTL (0x05) → CONFIRM (0x0a, returns 32-byte tx id) → WITNESSES
  (0x0f, returns 64-byte Ed25519 sig). Layouts reverse-engineered from ledgerjs
  v7 and reimplemented in Java.
- Static serializers `serializeTxInit` / `serializeTxInput` / `serializeTxOutputBasic`
  / `serializeCoin`, unit-tested against hand-computed vectors
  (`LedgerSignTxSerializationTest`, 4 tests). INIT built for a Conway-aware app
  (leading set-tags flag + treasury/donation flags + witness count at end).
- Param records `LedgerTxInput`/`LedgerTxOutput`/`LedgerSignedTx`; concrete
  `LedgerHardwareWalletService.signAdaPayment(...)` (not on the SPI yet).
- Sign probe `LedgerSignProbe` + `./gradlew :wallet-app:ledgerSignProbe`: builds
  a self-send tx with CCL, hashes it, streams to the device, and asserts
  **device tx hash == host tx hash** (needs no funds/node — the device signs
  offline). That equality is the correctness gate.

**Two CBOR-matching knobs exposed as params** (must equal the host's canonical
CBOR, else the hashes differ): `tagCborSets` (Conway set tag 258) and
`outputFormat` (0 = legacy array vs Babbage map). Defaults: `false` / `0`. First
device run tells us if they're right; a mismatch is a param flip, not a rewrite.

**Hardware finding #1 (2026-07-13, app 7.3.0):** INIT rejected with `0x6e07`
(INVALID_REQUEST_PARAMETERS). Cause: the leading Conway tx-options field is an
**8-byte uint64 bitfield** (`serializeTxOptions` → `uint64Number_to_buf`), not a
1-byte flag; I'd sent 1 byte, shifting the whole INIT by 7 bytes. Fixed to 8
bytes (0 when no options; `TAG_CBOR_SETS` = bit 0). Also confirmed 7.3.0 (incl.
XS app on old Nano S) has supportsConway=true, so the rest of the INIT is right.

**Hardware finding #2 (2026-07-13):** first stream got past INIT but the tx
hashes differed. Dumped CCL's body CBOR: inputs are tagged `d9 0102` (**CBOR set
tag 258**, Conway) and outputs are legacy arrays `[addr, coin]`. So the device
must tag sets too → set `tagCborSets = true` (outputFormat stays 0 = legacy
array). CCL fee/ttl encodings matched.

**Hardware-verified (2026-07-13, app 7.3.0):** with `tagCborSets=true`,
`ledgerSignProbe` printed **`TX HASH MATCH ✓`** — device tx hash ==
host (CCL) tx hash `dd5f6578…5c70d9`, and the device returned a valid Ed25519
witness. The Ledger signTx serialization is correct end-to-end on real hardware.

**End-to-end submit path (2026-07-13):** `LedgerSignSubmitProbe` +
`./gradlew :wallet-app:ledgerSignSubmitProbe -PnodeUrl=…` — connects to a Yano
node (`YanoNodeBackend`), reads an ADA-only UTXO at the account's receive-0
address, builds a self-send, signs on the device, attaches a `VkeyWitness`
(payment pubkey from the account xpub + the device signature), submits via the
node's `TransactionProcessor`, and polls `txStatus` for confirmation. Needs the
account funded + the node synced.

**Hardware-verified end-to-end (2026-07-13, app 7.3.0):** signed a preprod
self-send on the Ledger and submitted it — **`CONFIRMED ✓` on-chain**, tx
`acec11b1a3c2e988fb045d7abdd5879942df11ba6b7dbbffe1194717c36fa8bd` in block
4931507. The device signature passed the node's Scalus ledger validation. HW-M3
fully done on hardware.

Note: `signAdaPayment` hardcodes `tagCborSets=true`/`outputFormat=0` to match CCL
0.8.0-pre4 — revisit if CCL's encoding changes.

## Wallet integration — watch-only foundation (2026-07-13)

First slice of wiring hardware accounts into the wallet app.

**Delivered (wallet-core, tested, seed path unaffected):**
- `StoredWallet` gains `deviceType` + `accountXpubHex`; `vaultFile` now optional
  (validation: exactly one of vault vs device key). `isHardware()` helper. Index
  read/write carries the two new fields (backward-compatible: absent → seed).
- `WatchOnlyWallet implements com.bloxbean.cardano.hdwallet.Wallet` — derives
  base/enterprise/stake addresses from the account xpub via
  `CIP1852.getPublicKeyFromAccountPubKey`; every private-key op throws. **Needed
  because CCL `Wallet.createFromAccountKey` wants the account *private* key, not
  a public xpub** — there is no built-in watch-only wallet.
- `StoredWalletRepository.addWatchOnlyWallet(...)` + `unlockWatchOnly(...)`;
  `WalletService.unlockWatchOnly(walletId)` → a view-only `Session`.
- `WalletAddressService.accountView` refactored off the key-bearing
  `getAccount()` to public methods + the stored profile (stake/drep), so it
  works for both seed and watch-only wallets.
- Test `WatchOnlyWalletRepositoryTest`: a watch-only wallet built from the
  ledgerjs Speculos-seed account key derives `addr_test1qpd9x…` (index 1) —
  exactly the device/ledgerjs address — and round-trips through the index.

**UI onboarding (2026-07-13):** contract gains `WalletItem.hardware` +
`listHardwareDevices` / `importHardwareWallet` / `unlockHardware`;
`DefaultWalletUiController` implements them (enumerate → `importAccount` →
`repository.addWatchOnlyWallet` → `unlockWatchOnly`). `OnboardingScreen` gains a
"Connect hardware wallet" button + flow, and the wallet list badges hardware
wallets and unlocks them passphrase-free ("Open"). Balance/receive/history then
run through the existing screens via `WatchOnlyWallet`.

**Device signing on Send (2026-07-13):** `HardwareSendService` builds an unsigned
ADA payment (reads receive-0 UTXOs, simple coin-selection, recipient + change,
dust-change folded into fee), and on confirm signs it on the device (reusing the
on-chain-proven `signAdaPayment`, `tagCborSets=true`/`outputFormat=0`), asserts
device hash == host hash, attaches the `VkeyWitness`, and submits. Wired into the
controller: `draftSend`/`confirmDraft` branch on `activeWallet.hardware()`;
staking + native-asset sends are guarded with a "not supported on hardware yet"
message. **Pending hardware run** (add a Ledger wallet in the UI, send preprod
ADA, approve on device). All wallet-core/-hardware tests green; everything
compiles.

**Memo/metadata fix (2026-07-13):** hardware send was dropping the memo — the
first cut built no auxiliary data. Now: `HardwareSendService` builds CIP-20
metadata (`MessageMetadata`) → `AuxiliaryData`, sets the body's
`auxiliaryDataHash`, and attaches the metadata to the submitted tx; the Ledger
stream sends the **AUX_DATA stage (0x08)** right after INIT with
`0x00 (ARBITRARY_HASH) || 32-byte hash`, and INIT flags aux-data present. The
device only gets the hash (folds it into the body it hashes); the tx-hash
equality check still guards it. `signTransaction`/`signAdaPayment` gained an
`auxiliaryDataHash` param (probes pass null). Pending hardware run: send with a
memo, confirm it shows on cardanoscan.

**Next (HW-M4):** certificates (delegation/registration), reward withdrawal, and
native-asset outputs in the signTx stream — then unblock staking/token sends for
hardware in the UI.
