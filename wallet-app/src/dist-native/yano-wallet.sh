#!/usr/bin/env bash
#
# Yano Wallet launcher — native distribution (ADR-044).
#
# Needs NOTHING installed: the wallet is an AOT-compiled binary and the bundled
# Yano node is a native executable too. No Java, no JRE, no runtime.
#
# Usage:
#   ./yano-wallet.sh                       # launch the wallet
#   ./yano-wallet.sh --network=preprod ... # pass-through CLI options
#
# Environment:
#   YANO_NODE_JAR   override the managed node binary (default: ./yano-node/yano)
#
set -e

# Resolve the real directory even when invoked through a symlink, so the node
# beside the binary is still found.
SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
    LINK_DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
    SOURCE="$(readlink "$SOURCE")"
    [[ $SOURCE != /* ]] && SOURCE="$LINK_DIR/$SOURCE"
done
DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"

WALLET="$DIR/yano-wallet"
NODE="${YANO_NODE_JAR:-$DIR/yano-node/yano}"

if [ ! -x "$WALLET" ]; then
    echo "Error: $WALLET is missing or not executable." >&2
    echo "       If you unzipped with a tool that drops permissions, run:" >&2
    echo "         chmod +x '$WALLET' '$NODE'" >&2
    exit 1
fi

# The node is optional: the wallet can connect to an external one, so a missing
# binary is a warning rather than a failure — but the one-click managed node
# will not be offered.
if [ ! -x "$NODE" ]; then
    echo "Warning: managed node not found at $NODE" >&2
    echo "         Only external-node connections will work." >&2
    exec "$WALLET" "$@"
fi

# NodeLocator reads yano.node.jar first and derives the node's working directory
# as the nearest ancestor holding config/ — here yano-node/, which carries
# config/network/<network>/ for genesis.
exec "$WALLET" -Dyano.node.jar="$NODE" "$@"
