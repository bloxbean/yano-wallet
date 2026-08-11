# ADR-040: Passkey / WebAuthn for the Wallet — Encryption + Unlock, Not Signing

## Status

Proposed (analysis / decision record)

Relates to ADR-034 (Hardware Signer / Ledger) and ADR-036 (Security-Key Vault
Second Factor). Answers a recurring product question: "can Yano be a *passkey
wallet* where signing happens in the secure enclave, like utxos.dev / Gero and
the EVM/Solana passkey wallets?"

## Date

2026-07-28

## Context

### The appeal

"Passkey wallets" promise: no seed phrase to remember, unlock with biometrics or
a tap, keys held in secure hardware, phishing-resistant. On EVM/Solana this has
become real via **smart-contract accounts** that verify a passkey's signature
on-chain. The question is whether Yano can do the same on Cardano — specifically
whether the **passkey / Secure Enclave can be the transaction signer**.

### The hard Cardano constraint: curve mismatch

- **Cardano transaction witnesses require Ed25519.** The ledger accepts only
  Ed25519 vkey witnesses (and Ed25519 key hashes in native scripts).
- **Passkeys, the Apple Secure Enclave, Android StrongBox/TEE, and WebAuthn sign
  with ECDSA over NIST P-256 (secp256r1)** — COSE `ES256`. The Apple Secure
  Enclave supports *only* P-256. Some FIDO2 security keys can create Ed25519
  (`EdDSA`, COSE `-8`) credentials, but platform enclaves cannot, and a WebAuthn
  assertion signs `authenticatorData ‖ SHA-256(clientDataJSON)` — a WebAuthn
  structure, not your raw 32-byte Cardano tx hash.

So a passkey/enclave signature is **not** a Cardano witness.

### Why the EVM/Solana smart-account workaround doesn't port (yet)

- EVM passkey wallets (ERC-4337) verify the P-256 signature **on-chain** via a
  P-256 precompile / verifier contract; Solana added a `secp256r1` precompile.
- **Cardano's Plutus signature builtins are Ed25519 and secp256k1 (ECDSA +
  Schnorr) — there is no `secp256r1` / P-256 verifier builtin** (as of writing;
  its addition has been discussed but is not on mainnet — re-check for a newer
  CIP). So a Plutus "smart-contract account" cannot verify a passkey's P-256
  signature either.

**Conclusion: a passkey/Secure-Enclave *signer* for Cardano is not feasible
today** — not natively (Ed25519 vs P-256) and not via a smart account (no P-256
in Plutus).

### What "Cardano passkey wallets" actually do (verified 2026-07)

They do **not** sign with the passkey. The Cardano key stays a normal **Ed25519**
key; the passkey is used for **authentication** and/or to **derive an encryption
key**:

- **GeroWallet** — the Ed25519 key is **AES-256-encrypted and stored on the
  device**; the passkey (Face ID / Touch ID / Windows Hello) is **passwordless
  login / unlock**. Passkey = unlock, not signer.
  (gerowallet.io/security)
- **utxos.dev (MeshJS Wallet-as-a-Service)** — the Ed25519 key is **split with
  Shamir's Secret Sharing** so no full key exists on their servers (non-
  custodial), plus **social login**, passkey-protected shards, and gas
  sponsorship. Passkey protects/unlocks a shard; it does not sign.
  (docs.utxos.dev, github.com/MeshJS/web3-sdk)

The load-bearing primitive both use is the **WebAuthn PRF extension**, which is
the browser surface of the **CTAP2 `hmac-secret`** extension: a hardware-held
secret + an input → a deterministic 32-byte key, used to **encrypt** the wallet
key with zero server knowledge. (developers.yubico.com PRF guide; Bitwarden /
Corbado PRF writeups.)

### Relation to what Yano already built (ADR-036 Y-M2)

**Yano already implements that exact primitive.** ADR-036 Y-M2 drives CTAP2
`hmac-secret` (== WebAuthn PRF) over USB-HID to derive a secret that encrypts the
seed vault. The difference from utxos.dev/Gero is packaging, not mechanism:

| | Yano (ADR-036) | utxos.dev / Gero |
| --- | --- | --- |
| Signer | Ed25519 seed (software) or Ledger | Ed25519 key (software / sharded) |
| Passkey role | encrypt vault + unlock | encrypt key/shard + unlock |
| Primitive | CTAP2 `hmac-secret` (USB-HID) | WebAuthn PRF (browser) |
| Authenticator | any FIDO2 key (e.g. YubiKey) | platform (Face ID/Touch ID) + keys |
| Extras | — | Shamir/MPC sharding, social login, gas sponsorship |

## Decision

Yano treats **passkey / FIDO2 as an encryption + unlock factor (ADR-036), never
as a Cardano signer.** This matches both the Cardano cryptographic reality and
the actual architecture of the "passkey wallets" on the market. Secure-element
**signing** for Cardano remains the **Ledger** path (ADR-034: Ed25519 + BIP32 +
on-device confirmation).

The marketing term "passkey wallet" is therefore reframed for the record: on
Cardano it means *passkey-derived encryption (PRF/hmac-secret) + authentication
over an Ed25519 key*, optionally sharded — **not** the passkey signing the chain.

## Options / roadmap (if we chase the passkey UX further)

1. **Passwordless PRF unlock** — derive the vault key from `hmac-secret` + the
   FIDO2 PIN alone (no passphrase). Small addition to ADR-036's v4 key-slots (a
   "passwordless slot"). UX = tap + PIN, exactly like a passkey login. Must
   require UV/PIN (touch-only passwordless would be possession-only).
   **IMPLEMENTED (2026-07-28)** — a "passwordless slot" in the v4 vault plus a
   "No passphrase — key + PIN only" enrol option in Settings.
2. **Platform authenticators (Touch ID / Windows Hello)** — use the OS
   WebAuthn/platform API (which exposes PRF on recent macOS/Windows) instead of
   raw CTAP-HID, so users can unlock with a fingerprint, no USB key. Non-trivial
   for a JavaFX desktop app (no browser) — a research item.
3. **Sharding / social recovery (WaaS-style)** — Shamir/MPC split of the Ed25519
   key with passkey-protected shards + social login (the utxos.dev model). A
   large, different product direction with server-side infrastructure; out of
   scope for the self-custody desktop wallet, recorded for completeness.
4. **True passkey *signing* (future, only if primitives land)** — either (a)
   Ed25519-capable FIDO2 authenticators **plus** a Plutus **script account** that
   verifies the WebAuthn Ed25519 assertion on-chain (R&D: on-chain
   `clientDataJSON` parsing is costly; every account becomes a script address
   with per-tx execution cost), or (b) **`secp256r1` added to Plutus builtins**,
   enabling EVM-style P-256 smart accounts. Revisit if either arrives.

## Consequences

- Sets the record straight so the team doesn't chase an infeasible
  "enclave-signs-Cardano" design: for Cardano, "passkey" = encryption + auth over
  an Ed25519 key.
- Validates ADR-036 as the correct, standards-aligned use of FIDO2 for a Cardano
  wallet — we already ship the same `hmac-secret`/PRF core the passkey wallets
  use.
- Gives a concrete, ordered path toward closer passkey UX (passwordless PRF
  unlock → platform authenticators → optional sharding), and names the exact
  primitive (P-256 in Plutus, or Ed25519-WebAuthn + script accounts) that would
  unlock real passkey *signing* later.

## Sources

- utxos.dev / docs.utxos.dev; `github.com/MeshJS/web3-sdk` (Shamir sharding,
  client-side multi-shard, social login).
- gerowallet.io/security (passkey passwordless login; AES-256 on-device key).
- Yubico WebAuthn PRF developer guide; Bitwarden & Corbado PRF/passkey articles
  (PRF == CTAP2 hmac-secret; used to derive encryption keys, no server key access).
- CTAP 2.1 (hmac-secret); WebAuthn L3 (PRF extension); COSE algorithms (ES256
  P-256, EdDSA Ed25519).
- Cardano/Plutus: builtin signature verification limited to Ed25519 + secp256k1
  (ECDSA/Schnorr); ADR-034 (Ledger Ed25519 signer), ADR-036 (hmac-secret vault).
