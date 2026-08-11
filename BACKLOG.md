# Yano Wallet — Backlog (Epics)

Known gaps and planned work, kept out of the ADRs so status is scannable.
Everything **core** is device/on-chain-verified as of 2026-07-17 (see ADR-033/034/035
trackers); these epics are the delta to "no known gaps + publicly releasable".

| # | Epic | Status | Notes |
|---|------|--------|-------|
| E1 | **Hardware reward withdrawal — device test** | code + tests done | Needs rewards to exist; plan: devnet run (`HardwareStakeService.buildWithdrawal`). |
| E2 | **DRep unregister — click-through** | built | Deposit-reclaim flow (`draftDRepDeregistration`); never exercised on-device. Cheap win. |
| E3 | **Hardware signData (CIP-8)** | not built | Ledger app has a message-signing instruction; dApps needing `signData` on hardware currently get a clear error. |
| E4 | **dApp txs with certs/withdrawals on hardware** | not built | `LedgerTxTranslator` rejects them with a plain message; needed for staking/governance dApps with Ledger. Cert translation exists for wallet-built txs — reuse it. |
| E5 | **CIP-95 governance for dApps** | not built | `getPubDRepKey`/`getRegisteredPubStakeKeys` etc.; advertise via `getExtensions`. Enables govtool-style dApps. |
| E6 | **M5 distribution + transport hardening** | designed (ADR-035) | Chrome/Firefox/Edge listings (pinned key → stable id), privacy policy, **Native Messaging** host + manifest (installer-registered), retire the localhost WS for released builds. Pairs with code-signing. |
| E7 | **Release engineering — first real run** | workflow written | Never run: tag `v<version>` to let `release.yml` build all four platforms, verify Win/Linux packages (only macOS built locally), run the portable BYO-Java zip on a clean Java-25 machine. `build.yml` (compile + test, no packaging) runs on every push to main and PR. |
| E8 | **Code-signing + notarization** | researched | macOS Developer ID ($99/yr) + notarytool; Windows Azure Trusted Signing (~$10/mo); wire into CI gated on secrets. Until then: unsigned-install docs. |
| E9 | **Multi-account support (CIP-1852)** | **built — ADR-037**, needs manual verification | MA-M1/M2/M3 done: grouped list, sidebar switcher, software + hardware add-account, restore discovery. Deferred polish: per-row balances, rename account, auto-scan at end of restore. |
| E10 | **HW-M5: more devices** | not built | Trezor (USB) and/or Keystone (QR, airgapped) behind the same `HardwareWalletService` SPI. |
| E11 | **HW-M6: Speculos CI** | not built | Ledger emulator in CI so the device protocol gets automated regression tests. |
| E12 | **YubiKey/FIDO2 vault factor** | designed (ADR-036) | Envelope v3, HMAC challenge-response mixed into the KDF; Y-M1 YubiKey OTP, Y-M2 FIDO2 `hmac-secret`. |
| E13 | **CIP-45 (cross-device connect)** | deferred by decision | Revisit if dApp adoption grows or a mobile/companion story appears (ADR-035 records the rationale). |
| E14 | **M3 leftovers** | minor | Multi-tab behavior, bridge lifecycle polish, per-account CIP-30 nuances (after E9). |
| E15 | **Backend flavors — yaci-store / Yaci DevKit** | planned — ADR-038 | Money path already works (wallet is Blockfrost-shaped; CCL's BF services never call `/genesis`). Needs: flavor probe (`/status` 404 ⇒ yaci-store), pill via `/blocks/latest`, DRep via `/governance-state/dreps/{id}`, devkit preset. Blocker: **yaci-store exposes no protocol magic** → fingerprint workaround + refuse-mainnet rule. |
| E16 | **yaci-store: expose network magic** | **filed — [yaci-store#1018](https://github.com/bloxbean/yaci-store/issues/1018)** | Asks for `/api/v1/genesis` (or `network_magic` on `/network`), plus an indexer-lag indicator as a secondary. Filed generically — **it does not mention this wallet** (stealth). Once shipped: restores the plain magic check for both flavors, drops ADR-038 §2b entirely, and removes the hardcoded "devnet == magic 42" assumption. |

Ordering is not priority; pick by need. Cheap verification wins: E1, E2, E7.
