# Shelley wallet address discovery

Balance discovery scans receive (`1852'/1815'/account'/0/index`) and change
(`1852'/1815'/account'/1/index`) addresses independently. Each chain stops after
20 consecutive addresses with no transaction history by default. Spent addresses
reset the gap, just like funded addresses. Watch-only accounts derive both chains
from the stored account public key. Account discovery probes both chains too.

The previous scan stopped at receive index 99 and never queried change addresses.
It could therefore omit funds in restored wallets, including older Yoroi wallets.

The scan now continues to the unused gap, with a safety ceiling of 10,000 addresses
per chain. Reaching that ceiling without a gap is an **incomplete scan**, not a
successful balance. For unusually large accounts, increase the JVM property
`yano.wallet.scan.max-addresses-per-chain` (for example through
`JAVA_TOOL_OPTIONS=-Dyano.wallet.scan.max-addresses-per-chain=20000`) and retry.
This restarts discovery with the larger bound; it does not persist a scan cursor.

## Nodes without address history

The pinned Yano `0.1.0-pre13` distribution does not serve address transaction
history. Its `404` is different from a definite empty history result. A missing
Yano route, or a `503` explicitly identifying a disabled address-history index,
uses bounded UTxO-only recovery instead of failing the balance screen.

Recovery scans indices **0–199 on both chains** by default, without stopping at
empty-address gaps. The dashboard calls this **Known balance — scan incomplete**
and displays the exact scanned ranges. More funds may exist outside those ranges;
without history, no gap-based algorithm can establish completeness. A range can
extend further if history becomes unavailable after discovery has already advanced.
Each refresh retries history, so restoring the endpoint restores normal gap scans.

For a deeper recovery scan, launch with:

```bash
JAVA_TOOL_OPTIONS=-Dyano.wallet.scan.historyless-addresses-per-chain=1000 ./gradlew :wallet-app:run
```

The general 10,000-address safety ceiling still applies. Wider recovery windows
make more node requests and take longer; dashboard refreshes do not overlap.
Transport errors, generic server errors, malformed responses, and failed UTxO pages
still fail the refresh. Blockfrost-style stores' address-not-found `404` is treated
as unused, per their endpoint convention. Account-profile discovery still requires
history; UTxO-only recovery applies to addresses within an already opened account.

## Payments and scope

Software ADA/native-asset payments use the same discovery result to enumerate
funded addresses. Signing preserves the full derivation path, including the change
role, and returns change to the existing primary receive address. Transaction
previews include the discovered payment credentials in wallet ownership. An
incomplete recovery scan makes the preview unchecked, since a partial ownership
set must not be used to assert a complete value difference.

This change covers Shelley **base** addresses. Byron recovery is intentionally
out of scope. Enterprise-address discovery, hardware payments, the existing primary-address-only
staking/governance/minting paths, and CIP-30 address selection/signing are not
expanded by this change. The standard unused-address gap still applies; a wallet that used
addresses beyond a gap needs a larger gap setting through the scan API.
