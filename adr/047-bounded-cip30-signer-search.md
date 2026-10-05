# ADR-047: Bounded multi-address signer search

- Status: Implemented in development; real extension/ledger qualification pending
- Date: 2026-09-08
- Related: ADR-035 (CIP-30), ADR-037 (HD accounts), ADR-042 (transaction review)
- Scope: Software-wallet CIP-30 `signTx` and `signData`; not hardware signing

## Problem

The desktop can derive many receive addresses but the dApp transaction signer previously
used only receive index 0. An account enrolling payment keys from indexes 0, 1 and 2 could
collect only the first witness. Address-use discovery is insufficient: an unused address
can still be an explicit transaction authority.

## Final search rule

1. Restrict search to the currently unlocked HD account. Never try other HD accounts or
   seed groups implicitly. Only payment chains receive (role 0) and change (role 1) are searched.
2. First examine public paths known in this unlock session: addresses displayed/exported
   through the address view and funded paths discovered by balance scanning. Duplicates
   are removed. These exact known indexes may lie beyond the fallback window; do not derive
   the intervening range just because a high index is known. Cap known paths at 10,000.
3. Then examine receive indexes 0–29 and change indexes 0–29, irrespective of usage or gaps.
   Stop once every outstanding target has matched. This means 30 per chain, not 30 combined.
4. If hashes remain unmatched, ask explicitly whether to extend this request through index
   49 on both chains. Declining expansion retains the initial result; it does not authorize
   signing. Never extend automatically or beyond 49 except for exact known paths from step 2.
5. Targets come from transaction-body `required_signers` and payment credentials of resolved
   spending/collateral inputs. Do not sign merely because an address appears in outputs or
   reference inputs. Resolve inputs against the selected backend and check their identities;
   unresolved inputs fail closed. Script-payment inputs do not imply payment-key witnesses.
6. Retain the existing single-account stake-key relevance check for required signers,
   withdrawals and certificates; do not derive stake keys for every payment index. This
   increment does not add discovery of arbitrary native-script-only, governance or other
   certificate authorities that are not named in required signers or input credentials.
7. Pre-existing vkey witnesses must verify against the original body before they count as
   satisfied. Do not return them again or add duplicate/unrelated primary-payment witnesses.
8. Show the selected HD account, matched derivation paths, search window and unmatched hashes
   before approving. One approval signs all listed keys over the same original body bytes.
   Expansion and final approval are separate decisions. An unmatched hash may belong to
   another wallet, another account or an index outside the search; do not label it invalid.
9. For `partialSign=true`, return the reviewed relevant witnesses and leave unmatched targets
   to other participants. No matches is an explicit failure. For `partialSign=false`, reject
   if any of the collected target hashes remain unmatched; this is not a ledger-validation
   promise or a general native-script satisfiability solver.
10. Bind approval to the exact request bytes, partial-sign flag, unlocked session and backend
    connection. Consume the approval once. Account/network changes reject and require review
    again. Sign the original CBOR, never a reconstructed body. Search makes no signatures.

Input resolution plus derivation has a 10-second deadline per search attempt. At most two
search workers and two queued attempts are allowed; cancellation or failure signs nothing.
Review accepts at most 64 KiB transaction bytes and 256 unique spending/collateral inputs.
These are defensive client limits, not claims about ledger transaction limits.

## Discovery and compatibility

Known-path metadata is currently session-local. A fresh unlock/seed restoration searches the
30/50 windows until high paths are displayed or rediscovered. Persistent public path metadata
is a separate enhancement; never imply a gap scan recovers every unused enrolled key.

`getUsedAddresses` remains an address-use API, not a complete key inventory. Do not put unused
addresses into it to work around a dApp. Kavach's transaction precheck was adjusted to call
`signTx` without treating those lists as proof of the wallet's entire signing capability;
its backend still validates witness membership and signatures on the unchanged transaction.

This implementation leaves `signData` at its existing index-0 behavior. COSE multi-address
lookup needs its own address binding and consent work. Hardware paths are not expanded.

## Validation

`DappSignerSearchTest` covers unused receive 0/1/2, change keys, 29/30/49/50 boundaries,
known higher indexes, selected-account isolation, input/collateral versus output/reference
ownership, missing/mismatched input resolution, existing witness verification and duplicate
suppression, relevant stake keys, malformed/bounded requests, and multi-key signatures over
an indefinite-CBOR regression body.

`Cip30SignerSearchApprovalTest` exercises the actual gate-to-signer wiring: one approval for
multiple keys, explicit extension, declined extension with partial signing, full-sign rejection,
no-match rejection, user rejection, session/network/body changes, one-use tickets and generated
high-index metadata. Existing signer, connector, wallet and application tests also run.

These are local cryptographic and integration-of-components tests. They do not claim an actual
extension session, hardware approval or full node validation of the new multi-address workflow.
Keep Kavach's pending setup/backend alive when restarting only Yano to qualify it interactively.

## COSE data-signing extension (2026-09-08)

The same software-account search now applies to CIP-30 `signData`: known payment
paths, then receive/change 0–29, then explicit extension to 0–49. Targets come only
from the requested address's payment credential; key reward addresses retain the
single selected-account stake key. The current bounded address profile accepts key
enterprise and key-payment base addresses, plus key reward addresses; other address
forms fail closed. Network mismatches and unmatched keys reject without falling back
to receive index 0. There are no node/history/gap queries during data-key discovery.

The approval displays the matched path, full requested address and payload hex, and
states that a data signature can authorize later actions. A one-use ticket binds
address and payload to the reviewed session and backend connection; changing any of
these, rejecting consent or calling the signer without a ticket fails closed.
The bounded search executor/timeouts are shared with transaction signing.

Kavach exposes an explicit pending wallet-credential selector. It may construct a
testnet enterprise address for that exact payment hash when the wallet does not list
it; Yano decides ownership and obtains consent. Kavach still verifies the resulting
COSE key/signature against the expected credential and proof digest. It never tries
another authority automatically after a signing failure. The iPhone approval path
and fee-wallet transaction-signature path remain distinct.

Regression tests cover unused receive/change keys, verifiable COSE evidence and exact
returned public keys, 30/50 boundaries, known high indexes, other-account/network and
malformed-address rejection, explicit expansion, denied consent, request mutation,
account/network switches, and one-use approvals. Full core/connector/app tests and
Kavach frontend build/tests pass. Live user-wallet confirmation is recorded separately.
