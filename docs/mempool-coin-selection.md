# Yano mempool-aware coin selection

Yano transaction builders request `include_mempool=true` on address/payment-
credential listings and individual output resolution. The node excludes pending-
spent inputs and includes unspent pending change, with filtering and ordering
before pagination. The wallet also applies its local input reservations.

The node must support the published listing contract. This was verified against
port 7070 using the supplied test address: the overlay excluded a confirmed
outpoint and returned a different pending output with `block: null`. A 200 alone
would not have established support. There is no custom capability-header check,
no new supplier class, and no confirmed-only fallback on lookup failures. Nodes
that silently ignore the parameter are outside this preview version's contract.

## Selection and submission

`YanoNodeBackend.selectionUtxoSupplier()` uses the existing `YanoUtxoSupplier`
with the node overlay and a local reservation tracker. Software ADA/native-asset sends, composed
transactions and hardware builders use this view. Address and payment-credential
lookups filter the same outpoints. Full listings continue past fully excluded
pages; explicit pages are formed after filtering, preserving server order.
Lookup failures abort selection rather than returning partial funds.

The Yano transaction processor parses regular inputs from the submitted CBOR and
reserves them before sending the HTTP request. This covers software, hardware
and dApp submissions through that processor. A stale draft using a reserved input
is rejected locally before another request is made. Reference inputs are not
reserved. Pending outputs returned by the node are eligible, including change for subsequent
sends. The wallet does not construct or resubmit parent transactions itself.

The wallet connection persists reservations in the network's `pending-inputs.json`
file, independently of the history display records. This survives wallet restarts
and reconnects, and covers hardware submissions whose history records omit CBOR.
Storage errors block submission/selection. This is local tracking for one wallet
application; it is not a reservation service shared by other wallet apps or
concurrently running processes. Diagnostic backends without a configured storage
path track only for their process lifetime. Existing submissions made before this
tracking was installed cannot be reconstructed from this file.

## Refresh and failures

Before selection/submission the tracker refreshes against the node:

- A definite HTTP 4xx rejection releases that submission's reservations immediately.
- Confirmation removes its reservations once the node UTxO index is caught up.
- Passing the transaction's upper validity slot also releases reservations once
  the index is caught up. There is no arbitrary wall-clock unlock.
- Transport errors and HTTP 5xx responses have uncertain outcomes, so reservations
  remain until confirmation or expiry. Lookup failures never unlock inputs.

The history UI's existing five-minute “failed” timeout is only an advisory guess,
not a definite node rejection, and does not release input reservations. The current
transaction lookup cannot distinguish a dropped mempool transaction from one
still pending. A transaction without an upper validity bound cannot be safely
expired by this mechanism; it needs confirmation or a definitive rejection.

A submission conflict still discards the stale draft. The Send screen offers
**Rebuild & review**, which refreshes selection and requests a new approval; it
never automatically resubmits the signed transaction. Spending from another
wallet can still cause a node conflict because its submissions are unknown here.
When Yano names the claiming transaction and input in its conflict response, the
wallet persists that exact input under its actual owner. Rebuild excludes it,
while releasing the rejected draft's other inputs. The existing confirmation
refresh clears it once the owner is indexed. An unknown owner's TTL cannot be
inferred from the rejected draft: if the owner is dropped rather than confirmed,
the current API cannot prove that it is safe to unlock this learned reservation.

## Unchanged views

`utxoSupplier()` remains the confirmed view for balances and existing CIP-30
queries. Confirmed history is unchanged. Yaci DevKit uses its existing supplier
and processor, with no local exclusion or Yano-specific query parameters.

## Tests

`PendingInputsTest` and `YanoMempoolViewTest` cover actual ADA and token draft
construction using pending change, exclusion
across server pages, explicit pagination, payment credentials and ordering,
restart persistence, rejection and expiry cleanup, uncertain outcomes, lagging
indexes, lookup/storage failures, stale-draft rejection and non-Yano isolation.
Tests use a stub node and public fixture keys; they do not submit user funds.

Listing and individual-output errors remain explicit. `block: null` is accepted
for pending outputs; it does not alter the confirmed balance/history view.
