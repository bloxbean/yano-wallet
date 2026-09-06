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

## Node requirements

The node must serve address transaction history. Empty history must be a definite
answer, not an unavailable or disabled index. Yano's `404`, `503`, other history
errors, malformed responses, and failed UTxO pages now fail the balance refresh.
The dashboard displays the failure, including on periodic refreshes, instead of
showing a partial or stale total. Enable the address-history index on a node
version that supports it before retrying; older releases without that endpoint
cannot perform reliable gap discovery. Blockfrost-style stores' address-not-found
`404` is treated as unused, per their endpoint convention.

## Payments and scope

Software ADA/native-asset payments use the same discovery result to enumerate
funded addresses. Signing preserves the full derivation path, including the change
role, and returns change to the existing primary receive address. Transaction
previews include the discovered payment credentials in wallet ownership.

This change covers Shelley **base** addresses. Byron recovery is intentionally
out of scope. Enterprise-address discovery, hardware payments, the existing primary-address-only
staking/governance/minting paths, and CIP-30 address selection/signing are not
expanded by this change. The standard unused-address gap still applies; a wallet that used
addresses beyond a gap needs a larger gap setting through the scan API.
