Yano Wallet — portable distribution (bring your own Java)
=========================================================

This zip runs on a Java runtime you already have installed. It is smaller than
the native installer but needs Java; the installer bundles its own runtime.

Requirements
------------
  * Java 25 or newer (a full JDK or JRE), on your PATH or pointed to by JAVA_HOME.
    The wallet's one-click local ("managed") node runs under the same Java, so a
    complete Java 25 runtime is required — not a cut-down one.

Run
---
  macOS / Linux:   ./run.sh
  Windows:         run.bat

  Extra options pass straight through, e.g.:
      ./run.sh --network=preprod

Layout
------
  run.sh / .bat   launcher scripts (they pick the JavaFX build for your OS)
  yano-node/              the bundled Yano node distribution (jar + config/)
  lib/                    the wallet app, JavaFX, and all dependencies

Notes
-----
  * This build's JavaFX and hardware-wallet natives are for THIS operating
    system + CPU architecture; download the build matching your machine.
  * Wallet data lives in ~/.yano-wallet (override with --data-dir=<path>).
  * No Java, or an older Java? Use the native installer instead:
    Yano Wallet .dmg (macOS) / .msi (Windows) / .deb (Linux).

One archive runs on every platform: lib/platform/ carries JavaFX for macOS
(Intel and Apple Silicon), Windows and Linux (x86_64 and arm64), and the
launcher puts only the matching set on the classpath.
