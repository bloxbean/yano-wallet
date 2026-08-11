# ADR-037: Multi-Account Support (CIP-1852)

## Status

Implemented (MA-M1/M2/M3, 2026-07-17) — pending manual verification

## Date

2026-07-17

## Context

A CIP-1852 seed derives many **accounts** (`m/1852'/1815'/account'`), each with its
own addresses, stake key, and DRep key. Yano currently exposes only account 0: the
UI creates/imports account 0 and never offers another. Users expect the standard
wallet UX (Daedalus/Eternl/Lace): one wallet → several accounts, an account
switcher, and "add account".

### What is already in place (verified 2026-07-17)

The core was built account-aware from the start — this ADR is mostly **exposure +
UI**, not a refactor:

- `StoredWallet` is **profile-per-account** with a **`seedId` grouping key** and an
  `accountIndex`; software accounts share one vault file per seed.
- `FileStoredWalletRepository.createAccount(seedId, name, passphrase)` **already
  exists**: finds the seed group, computes `max(accountIndex)+1`, unlocks the vault,
  derives, de-duplicates, persists. It is simply not exposed above the repository.
- `unlock` builds `Wallet.createFromMnemonic(network, mnemonic, profile.accountIndex())`
  — CCL's `Wallet` is pinned to that account, and `wallet.getAccountAtIndex(i)` is
  the *address* index within it. So sessions, signing (payment/stake/DRep keys),
  balance scan, staking, governance, and CIP-30 all already honor the profile's
  account. Nothing in the money path hardcodes account 0.
- Hardware paths derive from `profile.accountIndex()` (`LedgerBip32.paymentPath/
  stakePath/drepPath(accountIndex)`), and `importHardwareWallet(name, accountIndex)`
  already accepts an index — the Onboarding screen just passes `0`.

### Gaps

1. `WalletService` / controller / UI contract don't expose `createAccount`.
2. `WalletItem` lacks `seedId`, so the UI can't group accounts of one wallet.
3. No UI: flat wallet list, no switcher, no add-account.
4. Hardware: each import becomes its **own** seed group (`seedId = walletId`), so
   two accounts of the same Ledger don't group; no add-account flow.
5. Restore creates account 0 only — no account discovery.

## Decision

Keep the **profile-per-account model grouped by `seedId`** (it exists and every
subsystem already keys off the profile). Add the missing exposure and a
three-part UI: grouped wallet list, in-shell account switcher, add-account flows.

### UI flow and design

**1. Onboarding / "Your wallets" — grouped list.** Group profiles by `seedId`;
one card per wallet, one row per account (sorted by index):

```
┌─ Personal ────────────────────────────────┐
│  Account 0 · Personal        addr…x2v  ▸Open │
│  Account 1 · Trading         addr…9qf  ▸Open │
│  + Add account                               │
└──────────────────────────────────────────────┘
┌─ Ledger Nano X ──────────────────── 🔒 hw ─┐
│  Account 0 · Ledger main     addr…7at  ▸Open │
│  + Add account   (connect device)            │
└──────────────────────────────────────────────┘
```

**2. In-shell account switcher.** A chip in the sidebar under the brand
(`Personal · Account 1 ▾`). Clicking opens a popover listing the sibling accounts
of the active seed group plus `+ Add account`. Switching:

- **v1: switch = re-open.** Lock the session, run the normal unlock for the target
  profile (software: passphrase prompt; hardware: silent watch-only open). Simple,
  no new key-handling surface.
- **v2 (optional later): in-session sibling switch.** Deriving a sibling account
  without re-entering the passphrase requires retaining seed material in the
  session — a deliberate security trade-off to decide separately. Not in v1.

**3. Add account.**
- *Software:* dialog (account name, passphrase) → `WalletService.createAccount` →
  repo `createAccount` → open it. Default name "Account N".
- *Hardware:* device connected → derive next index in the group →
  `importHardwareWallet(name, nextIndex)` (exists) → optional on-device address
  confirmation (exists: `showReceiveAddress`). **Grouping fix:** when adding to an
  existing hardware group, reuse that group's `seedId` (repo gains
  `addWatchOnlyAccount(seedId, name, accountIndex, xpub)`); fresh imports keep
  starting a new group.

**4. Display conventions.** `WalletItem` gains `seedId` (and the wallet-group
name). Everywhere an account is shown: `"<group> · Account <n>"` with the
account's own name when set. Balance shown per account row (async, from the
existing balance service) in v1.1 — not a blocker.

**5. CIP-30.** dApps see the **active** account (already true — the bridge reads
the live session). Switching accounts changes what a connected dApp gets on its
next call; the origin allowlist stays account-agnostic in v1. Documented in the
connector README; per-account allowlists are E14 in the backlog.

**6. Restore discovery (last milestone).** After restoring a seed, probe accounts
1..N against the node (`isUsedAddress` on each account's first base address — the
M2 endpoint exists), stopping at the first unused account (BIP-44 account gap of
1). Offer the found accounts pre-checked; user confirms. Same discovery available
from "+ Add account" as "scan for used accounts".

## Milestones

- **MA-M1 — software accounts end-to-end.** ✅ Done. `WalletService.createAccount`;
  contract `createAccount(seedId, name, passphrase)` + `WalletItem.seedId` and
  `accountLabel()`; grouped Onboarding list; sidebar switcher (v1 re-open
  semantics). wallet-ui gained its first test source set (grouping rules,
  label formatting); wallet-core proves each account has its own stake key and
  DRep id.
- **MA-M2 — hardware accounts.** ✅ Done. `addWatchOnlyAccount(seedId, …)` +
  grouping (both watch-only paths now share one derive/dedup/persist helper);
  hardware "+ Add account" reads the next account's xpub from the device.
  On-device address verify remains available separately (Receive screen), not
  wired into the add flow.
- **MA-M3 — restore discovery + polish.** ✅ Discovery done: repo
  `discoverAccounts(...)` with a caller-supplied chain probe (one vault unlock,
  gap window per account, stops at the first empty account), controller wiring
  via the node's `isUsedAddress`, and a "Scan for existing accounts" →
  confirm-list flow on add-account. CIP-30 note added to the connector README.
  **Deferred polish** (were flagged "not a blocker"): per-row balances in the
  wallet list, rename account, and auto-offering the scan at the end of the
  restore flow (today the user runs it from "+ Add account").

## Consequences

- No storage migration: existing profiles are already valid single-account groups
  (their `seedId` stands). New accounts append profiles.
- The switcher makes lock/unlock flows more frequent — the v1 re-open choice keeps
  security semantics identical to today at the cost of a passphrase prompt per
  software-account switch.
- Every downstream feature (staking, governance, hardware, CIP-30, history)
  works per-account by construction, because it all reads the session profile —
  the main risk is UI state that caches account-0 assumptions; the switcher must
  refresh all screens (existing `Shell.navigate` refresh covers this).
- Hardware account discovery needs the device present (xpubs come from it);
  restore discovery (MA-M3) applies fully only to software seeds.
