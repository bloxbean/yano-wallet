#!/usr/bin/env bash
#
# Yano Wallet launcher — portable "bring your own Java" distribution.
#
# Requires a Java 25+ runtime on the PATH (or set JAVA_HOME). The wallet's
# managed node runs under the SAME Java, so a full JDK/JRE 25 is needed for the
# one-click local node. If you don't have Java, use the native installer
# instead (Yano Wallet .dmg / .msi / .deb) — it bundles its own runtime.
#
# Usage:
#   ./yano-wallet.sh                       # launch the wallet
#   ./yano-wallet.sh --network=preprod ... # pass-through CLI options
#
# Environment:
#   JAVA_HOME   JDK/JRE 25+ to use (else `java` on PATH)
#   JAVA_OPTS   extra JVM options
#
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "Error: no Java runtime found. Install Java 25+ (or set JAVA_HOME)," >&2
    echo "       or use the native installer, which bundles its own runtime." >&2
    exit 1
fi

# Require Java 25+ (the wallet and the bundled node are compiled for 25).
VER="$("$JAVA" -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')"
if [ -n "$VER" ] && [ "$VER" -lt 25 ] 2>/dev/null; then
    echo "Error: Java 25+ required (found Java $VER). Set JAVA_HOME to a newer JDK," >&2
    echo "       or use the native installer, which bundles its own runtime." >&2
    exit 1
fi

# $DIR/lib/* holds the app + JavaFX + all deps; yano-node/ is the bundled node
# distribution (yano.jar + config/, which holds per-network genesis).
# --enable-native-access is for hid4java (Ledger) and JavaFX FFM.
exec "$JAVA" $JAVA_OPTS \
    --enable-native-access=ALL-UNNAMED \
    -Dyano.node.jar="$DIR/yano-node/yano.jar" \
    -cp "$DIR/lib/*" \
    com.bloxbean.cardano.yano.wallet.app.YanoWalletApp "$@"
