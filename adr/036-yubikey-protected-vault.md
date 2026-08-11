# ADR-036: Hardware-Backed Second Factor for the Wallet Vault (YubiKey / FIDO2)

## Status

Proposed

Complements ADR-033 (Full-Node Desktop Wallet) and ADR-034 (Hardware Signer).
ADR-034 removes key exposure entirely by keeping the seed on a Ledger. This ADR
addresses the *other* wallet: the software (hot) wallet, whose seed lives in an
encrypted vault on disk. It adds a **middle tier** — a hot wallet whose vault
cannot be opened without a physical security key.

## Date

2026-07-16

## Context

### The gap

Today `FileWalletSecretStore` (wallet-core) stores the mnemonic in envelope **v2**:
Argon2id (m=256 MiB, t=3, p=2) over the passphrase → AES-256-GCM, fresh salt and
nonce per write (v1 PBKDF2 vaults migrate on unlock). The Argon2id parameters are
deliberately memory-hard because the file is offline-attackable.

But the vault's security still reduces to **one factor: the passphrase**. An
attacker who obtains the vault file *and* the passphrase — by keylogging, shoulder
surfing, reuse, or a weak choice — has the seed. Memory-hardness buys time against
brute force; it does nothing once the passphrase is known.

That is the whole gap this ADR closes: make possession of the file **plus** the
passphrase insufficient.

### Why not use the YubiKey as a Cardano signer

The obvious-sounding idea — "sign Cardano transactions with the YubiKey" — does
not work well, and we reject it:

- **PIV applet**: RSA and ECC P-256/P-384 only — **no Ed25519**. Dead end.
- **OpenPGP applet**: does support Ed25519, and a Cardano vkey witness is just an
  Ed25519 public key + a 64-byte signature over the tx body hash, so a *single-key*
  wallet is technically conceivable. But there is **no BIP32-Ed25519 derivation**
  on the device and no chain code, so CIP-1852 HD derivation is impossible: no
  derived payment/change addresses, no stake or DRep keys the standard way, and no
  24-word seed to restore into any other wallet.
- We already have a **Ledger** integration (ADR-034) with a purpose-built Cardano
  app: BIP32-Ed25519, on-device address display, and structured transaction
  confirmation. A YubiKey signer would be strictly worse and non-standard.

**Decision: the security key is an unlock factor, not a signer.**

## Decision

Add an **optional hardware-backed second factor to the vault KDF** — the model
KeePassXC uses. Envelope **v3** extends v2 with a factor descriptor and a
per-vault random challenge; the derived key becomes:

```
factorSecret = HMAC(hardware-key secret, challenge)      # never leaves the device's control
key          = Argon2id(passphrase ‖ factorSecret, salt) # existing v2 params
vault        = AES-256-GCM(key, mnemonic)
```

The challenge is stored in the envelope (it is not secret). The device's secret
never leaves the key, so the vault is **undecryptable without the physical key**,
even with the file *and* the passphrase. v2 vaults keep working unchanged; adding
or removing the factor is a re-encrypt of the envelope.

### Protocol choice, sequenced

- **Y-M1 — YubiKey OTP HMAC-SHA1 challenge-response.** Slot 1/2 programmed with an
  HMAC-SHA1 secret (YubiKey Manager, one-time): ≤64-byte challenge → 20-byte
  response, over a documented USB-HID feature-report protocol. Simple, proven by
  KeePassXC, and it reuses what we already have — `hid4java` plus the pure-Java HID
  protocol precedent from ADR-034 (this protocol is *simpler* than the Ledger's).
  Costs the user a one-time slot setup and is YubiKey/OnlyKey-specific.
- **Y-M2 — FIDO2 `hmac-secret` (CTAP2).** The modern equivalent: works with **any**
  FIDO2 key (YubiKey 5, Nitrokey, SoloKey, Titan…), no slot programming — the
  wallet enrols a credential and derives the secret per assertion, with PIN/touch.
  Strictly better for users; more protocol work (CTAP2 CBOR over HID + the PIN/UV
  auth protocol), so it follows Y-M1 rather than blocking it.

Device code lives **only in the UI JVM**, never the node — the same rule ADR-034
sets for `wallet-hardware`.

### Recovery — the load-bearing caveat

If the security key is lost, the vault **cannot be opened**. The 24-word mnemonic
remains the real backup and restores the wallet anywhere. Therefore:

- Enrolment must state this **unmissably**, and require the user to confirm they
  still hold their recovery phrase.
- Support **enrolling a backup key** (a YubiKey has two slots; FIDO2 allows several
  credentials) so a single lost key is not a crisis.
- Removing the factor (downgrade v3 → v2) must be possible **with** the current key
  and passphrase.

### Honest limits

- This protects the vault **at rest**. After unlock the key material is in process
  memory, so malware present at signing time can still abuse it. A Ledger never
  exposes the seed at all.
- So this is a deliberate **middle tier**, not a hardware-wallet substitute:
  - base — software wallet + passphrase,
  - **middle — software wallet + security key (this ADR)**,
  - best — Ledger (ADR-034).
- It raises the bar decisively against the realistic attacks it targets: a stolen
  vault file (offline cracking) and a stolen file plus a known/keylogged passphrase.

## Consequences

- A new envelope version (v3) and an unlock flow with a physical touch step; v2
  vaults continue to work and are upgraded only on request.
- New USB-HID device code beside the Ledger stack, reusing `hid4java`; the
  wallet-hardware module grows a second, unrelated device protocol (keep them
  cleanly separated — a security key is not a signer).
- A lost key without a recovery phrase is **permanent loss** — the UX must make
  that impossible to miss, and backup-key enrolment should be encouraged at setup.
- Y-M2 (FIDO2) widens support beyond YubiKey and removes the slot-programming
  step; until then, Y-M1 documents the one-time YubiKey Manager setup.
