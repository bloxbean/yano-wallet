import {
  BrowserWallet,
  KoiosProvider,
  Transaction,
  resolveDataHash,
  resolvePaymentKeyHash,
  resolvePlutusScriptAddress,
} from "@meshsdk/core";
import type { PlutusScript, UTxO } from "@meshsdk/core";
import { DEFAULT_PROTOCOL_PARAMETERS } from "@meshsdk/common";

// Self-contained signTx + submitTx test (ADR-035, CIP30-M2): connect Yano, build
// a "send ₳2 to yourself" preprod transaction with MeshJS (the wallet supplies
// the inputs; protocol params are the standard defaults, so no chain provider /
// API key is needed for a simple send), then sign + submit through Yano.

const connectBtn = document.getElementById("connect") as HTMLButtonElement;
const sendBtn = document.getElementById("send") as HTMLButtonElement;
const lockBtn = document.getElementById("lock") as HTMLButtonElement;
const unlockBtn = document.getElementById("unlock") as HTMLButtonElement;
const info = document.getElementById("info") as HTMLDivElement;
const lockInfo = document.getElementById("lockInfo") as HTMLDivElement;
const statusEl = document.getElementById("status") as HTMLDivElement;

let wallet: BrowserWallet | null = null;

// --- Plutus script test (ADR-035 M4): lock at always-succeeds, then unlock. ---
// The unlock is a genuine script transaction: script input + redeemer +
// collateral + script-data-hash → the Ledger signs in PLUTUS mode.
const ALWAYS_SUCCEEDS: PlutusScript = { code: "4e4d01000033222220051200120011", version: "V1" };
const SCRIPT_ADDRESS = resolvePlutusScriptAddress(ALWAYS_SUCCEEDS, 0); // testnet
const LOCK_AMOUNT = "3000000";
const LOCK_STORE = "yano-script-lock";
// Koios preprod, routed through the Vite dev-server proxy (see vite.config.ts):
// browser-direct Koios calls get blocked (CORS/Cloudflare), so the dev server
// forwards /koios/* server-to-server instead.
const KOIOS_BASE = "/koios";
const koios = () => new KoiosProvider(KOIOS_BASE);

function savedLock(): { txHash: string; datumValue: string } | null {
  try {
    const raw = localStorage.getItem(LOCK_STORE);
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
      `Submitted ✓ <code>${hash}</code><br>` +
        `<a href="https://preprod.cardanoscan.io/transaction/${hash}" target="_blank">View on cardanoscan</a>`,
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

    localStorage.setItem(LOCK_STORE, JSON.stringify({ txHash: hash, datumValue }));
    refreshLockUi();
    show(
      `Locked ✓ <code>${hash}</code><br>Wait for confirmation (~1 block), then unlock.<br>` +
        `<a href="https://preprod.cardanoscan.io/transaction/${hash}" target="_blank">View on cardanoscan</a>`,
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
    const provider = koios();
    const scriptUtxo: UTxO = {
      input: { txHash: lock.txHash, outputIndex: 0 },
      output: {
        address: SCRIPT_ADDRESS,
        amount: [{ unit: "lovelace", quantity: LOCK_AMOUNT }],
        dataHash: resolveDataHash(lock.datumValue),
      },
    };
    show("Fetching current preprod cost models…", "info");
    // The script-data-hash covers the cost-model "language views". Mesh's baked-in
    // default cost models are stale vs live preprod (InvalidScriptDataHash at the
    // node), and its Protocol type can't carry cost models — so inject the current
    // ones ([V1, V2, V3]) straight from Koios into the tx builder.
    const epochParams = await (
      await fetch(KOIOS_BASE + "/epoch_params?order=epoch_no.desc&limit=1")
    ).json();
    const rawModels = epochParams[0].cost_models;
    const costModels = typeof rawModels === "string" ? JSON.parse(rawModels) : rawModels;

    show("Building unlock transaction (script input + redeemer + collateral)…", "info");
    // No evaluator: Koios's Ogmios evaluate passthrough rejects anonymous browser
    // POSTs (ERR_NETWORK). Mesh's default redeemer budget (7M mem / 3B steps) is
    // plenty for always-succeeds.
    const tx = new Transaction({ initiator: wallet, fetcher: provider });
    tx.txBuilder.setCostModels([costModels.PlutusV1, costModels.PlutusV2, costModels.PlutusV3]);
    tx.redeemValue({ value: scriptUtxo, script: ALWAYS_SUCCEEDS, datum: lock.datumValue });
    tx.sendValue(await wallet.getChangeAddress(), scriptUtxo);
    const unsigned = await tx.build();

    show("Waiting for signature — approve in Yano, then confirm the Plutus tx on the device…", "info");
    const signed = await wallet.signTx(unsigned, true);
    show("Submitting…", "info");
    const hash = await wallet.submitTx(signed);

    localStorage.removeItem(LOCK_STORE);
    refreshLockUi();
    show(
      `Unlocked ✓ — a Plutus script transaction signed on your Ledger.<br><code>${hash}</code><br>` +
        `<a href="https://preprod.cardanoscan.io/transaction/${hash}" target="_blank">View on cardanoscan</a>`,
      "success"
    );
  } catch (e) {
    show("Unlock failed: " + describe(e), "error");
  }
  refreshLockUi();
});

refreshLockUi();
