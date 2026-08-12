import {
  BrowserWallet,
  Transaction,
  resolveDataHash,
  resolvePaymentKeyHash,
  resolvePlutusScriptAddress,
} from "@meshsdk/core";
import type { PlutusScript, UTxO } from "@meshsdk/core";
import { DEFAULT_PROTOCOL_PARAMETERS } from "@meshsdk/common";
import { NETWORKS, selectNetwork, selectedNetwork } from "./networks";
import type { NetworkId } from "./networks";

// Self-contained signTx + submitTx test (ADR-035, CIP30-M2): connect Yano, build
// a "send ₳2 to yourself" transaction with MeshJS (the wallet supplies the
// inputs; protocol params are the standard defaults, so no chain provider /
// API key is needed for a simple send), then sign + submit through Yano.
//
// Runs against preprod or a local Yaci DevKit devnet (ADR-038) — pick with the
// selector. Only the chain-data source differs; every CIP-30 call below is
// identical, which is the point: the same dApp code drives both.

const connectBtn = document.getElementById("connect") as HTMLButtonElement;
const sendBtn = document.getElementById("send") as HTMLButtonElement;
const lockBtn = document.getElementById("lock") as HTMLButtonElement;
const unlockBtn = document.getElementById("unlock") as HTMLButtonElement;
const info = document.getElementById("info") as HTMLDivElement;
const lockInfo = document.getElementById("lockInfo") as HTMLDivElement;
const statusEl = document.getElementById("status") as HTMLDivElement;
const networkPicker = document.getElementById("network") as HTMLSelectElement;
const networkHint = document.getElementById("networkHint") as HTMLDivElement;

let wallet: BrowserWallet | null = null;

// --- Plutus script test (ADR-035 M4): lock at always-succeeds, then unlock. ---
// The unlock is a genuine script transaction: script input + redeemer +
// collateral + script-data-hash → the Ledger signs in PLUTUS mode.
const ALWAYS_SUCCEEDS: PlutusScript = { code: "4e4d01000033222220051200120011", version: "V1" };
const SCRIPT_ADDRESS = resolvePlutusScriptAddress(ALWAYS_SUCCEEDS, 0); // testnet
const LOCK_AMOUNT = "3000000";
// Keyed by network: a UTxO locked on preprod does not exist on a devnet, so
// unlocking must never reach across chains for it.
const lockStore = () => "yano-script-lock:" + selectedNetwork().id;

function savedLock(): { txHash: string; datumValue: string } | null {
  try {
    const raw = localStorage.getItem(lockStore());
    return raw ? JSON.parse(raw) : null;
  } catch (_) {
    return null;
  }
}

function refreshLockUi() {
  const lock = savedLock();
  lockInfo.textContent = lock ? "Locked ₳3 in tx " + lock.txHash.slice(0, 16) + "… (ready to unlock)" : "";
  unlockBtn.disabled = !wallet || !lock;
  lockBtn.disabled = !wallet;
}

function show(html: string, kind: "info" | "success" | "error") {
  statusEl.innerHTML = html;
  statusEl.className = kind;
  statusEl.style.display = "block";
}

/** A "view this tx" line, or nothing where the network has no explorer. */
function explorerLine(hash: string): string {
  const url = selectedNetwork().explorerTx(hash);
  return url ? `<br><a href="${url}" target="_blank">View on cardanoscan</a>` : "";
}

function refreshNetworkUi() {
  const network = selectedNetwork();
  networkPicker.value = network.id;
  networkHint.textContent = "Needs: " + network.hint;
  refreshLockUi();
}

networkPicker.addEventListener("change", () => {
  selectNetwork(networkPicker.value as NetworkId);
  // The connection is per-network in practice (different chain, different
  // UTxOs), and both testnets report CIP-30 networkId 0 so the demo cannot
  // detect a mismatch for you — reconnect deliberately.
  wallet = null;
  sendBtn.disabled = true;
  info.textContent = "";
  statusEl.style.display = "none";
  refreshNetworkUi();
});

function describe(e: any): string {
  const s = e && (e.message || e.info) ? e.message || e.info : String(e);
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

connectBtn.addEventListener("click", async () => {
  if (!(window as any).cardano?.yano) {
    show("window.cardano.yano not found — load the Yano extension and serve this over http.", "error");
    return;
  }
  connectBtn.disabled = true;
  show("Connecting… approve in the Yano app.", "info");
  try {
    wallet = await BrowserWallet.enable("yano");
    const used = await wallet.getUsedAddresses();
    const addr = used[0] || (await wallet.getChangeAddress());
    info.textContent = "Connected: " + addr;
    sendBtn.disabled = false;
    refreshLockUi();
    statusEl.style.display = "none";
  } catch (e) {
    show("Connect failed: " + describe(e), "error");
  }
  connectBtn.disabled = false;
});

sendBtn.addEventListener("click", async () => {
  if (!wallet) return;
  sendBtn.disabled = true;
  try {
    const addr = await wallet.getChangeAddress();
    show("Building transaction (send ₳2 to " + addr.slice(0, 24) + "…)", "info");

    const tx = new Transaction({ initiator: wallet, params: DEFAULT_PROTOCOL_PARAMETERS as any });
    tx.sendLovelace(addr, "2000000");
    const unsigned = await tx.build();

    show("Waiting for signature — approve in Yano…", "info");
    const signed = await wallet.signTx(unsigned, true); // partial sign

    show("Submitting…", "info");
    const hash = await wallet.submitTx(signed);

    show(
      `Submitted ✓ <code>${hash}</code>` + explorerLine(hash),
      "success"
    );
  } catch (e) {
    show("Failed: " + describe(e), "error");
  }
  sendBtn.disabled = false;
});

// Lock: an ordinary tx whose output sits at the script address with a datum
// hash (unique per wallet so unlock finds OUR utxo, not someone else's junk).
lockBtn.addEventListener("click", async () => {
  if (!wallet) return;
  lockBtn.disabled = true;
  try {
    const datumValue = resolvePaymentKeyHash(await wallet.getChangeAddress());
    show("Building lock transaction (₳3 → always-succeeds script)…", "info");
    const tx = new Transaction({ initiator: wallet, params: DEFAULT_PROTOCOL_PARAMETERS as any });
    tx.sendLovelace({ address: SCRIPT_ADDRESS, datum: { value: datumValue } }, LOCK_AMOUNT);
    const unsigned = await tx.build();

    show("Waiting for signature — approve in Yano (and on the device)…", "info");
    const signed = await wallet.signTx(unsigned, true);
    show("Submitting…", "info");
    const hash = await wallet.submitTx(signed);

    localStorage.setItem(lockStore(), JSON.stringify({ txHash: hash, datumValue }));
    refreshLockUi();
    show(
      `Locked ✓ <code>${hash}</code><br>Wait for confirmation (~1 block), then unlock.` +
        explorerLine(hash),
      "success"
    );
  } catch (e) {
    show("Lock failed: " + describe(e), "error");
  }
  refreshLockUi();
});

// Unlock: the real Plutus transaction — spends the script utxo with a redeemer,
// wallet collateral, and a script-data-hash. Signed on the Ledger in PLUTUS mode.
unlockBtn.addEventListener("click", async () => {
  const lock = savedLock();
  if (!wallet || !lock) return;
  unlockBtn.disabled = true;
  try {
    const network = selectedNetwork();
    const provider = network.provider();
    const scriptUtxo: UTxO = {
      input: { txHash: lock.txHash, outputIndex: 0 },
      output: {
        address: SCRIPT_ADDRESS,
        amount: [{ unit: "lovelace", quantity: LOCK_AMOUNT }],
        dataHash: resolveDataHash(lock.datumValue),
      },
    };
    show(`Fetching current cost models from ${network.label}…`, "info");
    // The script-data-hash covers the cost-model "language views", so the models
    // must be the CHAIN's current ones (see networks.ts).
    const costModels = await network.costModels();

    show("Building unlock transaction (script input + redeemer + collateral)…", "info");
    // No evaluator: Koios's Ogmios evaluate passthrough rejects anonymous browser
    // POSTs (ERR_NETWORK). Mesh's default redeemer budget (7M mem / 3B steps) is
    // plenty for always-succeeds.
    const tx = new Transaction({ initiator: wallet, fetcher: provider });
    tx.txBuilder.setCostModels(costModels);
    tx.redeemValue({ value: scriptUtxo, script: ALWAYS_SUCCEEDS, datum: lock.datumValue });
    tx.sendValue(await wallet.getChangeAddress(), scriptUtxo);
    const unsigned = await tx.build();

    show("Waiting for signature — approve in Yano, then confirm the Plutus tx on the device…", "info");
    const signed = await wallet.signTx(unsigned, true);
    show("Submitting…", "info");
    const hash = await wallet.submitTx(signed);

    localStorage.removeItem(lockStore());
    refreshLockUi();
    show(
      `Unlocked ✓ — a Plutus script transaction signed on your Ledger.<br><code>${hash}</code>` +
        explorerLine(hash),
      "success"
    );
  } catch (e) {
    show("Unlock failed: " + describe(e), "error");
  }
  refreshLockUi();
});

refreshLockUi();

refreshNetworkUi();
