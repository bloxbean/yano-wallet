package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletCreation;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalance;
import com.bloxbean.cardano.yano.wallet.nodeclient.NodeStatus;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headless CLI harness proving the M1 wallet stack against a running Yano
 * node: create/restore wallets in the encrypted vault, scan balance through
 * the node's Blockfrost-compatible API, build/sign/submit a payment, and wait
 * for on-chain confirmation. Dev/test tool only — not a user-facing CLI.
 *
 * <pre>
 * Usage: wallet-probe &lt;command&gt; [--option=value ...]
 *
 * Commands:
 *   status                              node status + network check
 *   create   --name=X                   create wallet, print mnemonic (backup!)
 *   restore  --name=X --mnemonic="..."  import wallet from mnemonic
 *   list                                list stored wallets
 *   balance  --wallet-id=X              scan UTXOs and print balance
 *   send     --wallet-id=X --to=addr --ada=1.5 [--wait]
 *
 * Common options:
 *   --network=devnet|preview|preprod|mainnet   (default devnet)
 *   --base-url=http://localhost:7070/api/v1/   (default)
 *   --data-dir=~/.yano-wallet                  (default; wallets under &lt;data-dir&gt;/&lt;network&gt;)
 *   --passphrase=...  or env YANO_WALLET_PASSPHRASE
 * </pre>
 */
public final class WalletProbe {

    public static void main(String[] args) {
        try {
            run(args);
        } catch (ProbeException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("ERROR: " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        if (args.length == 0) {
            throw new ProbeException("Missing command. Commands: status, create, restore, list, balance, send");
        }
        String command = args[0];
        Map<String, String> opts = parseOptions(args);

        WalletNetwork network = WalletNetwork.fromId(opts.getOrDefault("network", "devnet"));
        String baseUrl = opts.getOrDefault("base-url", YanoNodeBackend.DEFAULT_LOCAL_BASE_URL);
        FileStoredWalletRepository repository = new FileStoredWalletRepository(dataDirOf(opts, network), network);

        switch (command.toLowerCase(Locale.ROOT)) {
            case "status" -> status(network, baseUrl);
            case "create" -> create(repository, opts);
            case "restore" -> restore(repository, opts);
            case "list" -> list(repository);
            case "balance" -> balance(repository, network, baseUrl, opts);
            case "send" -> send(repository, network, baseUrl, opts);
            case "delegate" -> delegate(repository, network, baseUrl, opts);
            case "withdraw" -> withdraw(repository, network, baseUrl, opts);
            case "mint" -> mint(repository, network, baseUrl, opts);
            default -> throw new ProbeException("Unknown command: " + command);
        }
    }

    private static void status(WalletNetwork network, String baseUrl) {
        YanoNodeBackend backend = YanoNodeBackend.connectVerified(network, baseUrl);
        NodeStatus status = backend.nodeClient().getStatus();
        print("network", network.id(),
                "baseUrl", backend.nodeClient().baseUrl(),
                "slot", status.slot(),
                "blockNumber", status.blockNumber(),
                "utxoIndexEnabled", status.utxoIndexEnabled(),
                "utxoLagBlocks", status.utxoLagBlocks(),
                "utxoIndexCaughtUp", status.utxoIndexCaughtUp());
    }

    private static void create(FileStoredWalletRepository repository, Map<String, String> opts) {
        StoredWalletCreation creation = repository.createRandomWallet(
                required(opts, "name"), passphrase(opts));
        StoredWallet wallet = creation.wallet();
        print("walletId", wallet.id(),
                "name", wallet.name(),
                "network", wallet.networkId(),
                "baseAddress", wallet.baseAddress(),
                "stakeAddress", wallet.stakeAddress(),
                "mnemonic", creation.mnemonic());
    }

    private static void restore(FileStoredWalletRepository repository, Map<String, String> opts) {
        StoredWallet wallet = repository.importMnemonic(
                required(opts, "name"), mnemonic(opts), passphrase(opts));
        print("walletId", wallet.id(),
                "name", wallet.name(),
                "network", wallet.networkId(),
                "baseAddress", wallet.baseAddress(),
                "stakeAddress", wallet.stakeAddress());
    }

    private static void list(FileStoredWalletRepository repository) {
        for (StoredWallet wallet : repository.list()) {
            print("walletId", wallet.id(),
                    "name", wallet.name(),
                    "accountIndex", wallet.accountIndex(),
                    "baseAddress", wallet.baseAddress());
        }
    }

    private static WalletService walletService(FileStoredWalletRepository repository, WalletNetwork network,
                                               String baseUrl, Map<String, String> opts) {
        YanoNodeBackend backend = YanoNodeBackend.connectVerified(network, baseUrl);
        return new WalletService(
                repository,
                backend.utxoSupplier(),
                backend.protocolParamsSupplier(),
                backend.transactionProcessor(),
                new FilePendingTransactionStore(dataDirOf(opts, network).resolve("pending-transactions.json")),
                backend.ports());
    }

    private static void balance(FileStoredWalletRepository repository, WalletNetwork network,
                                String baseUrl, Map<String, String> opts) {
        WalletService service = walletService(repository, network, baseUrl, opts);
        WalletService.Session session = service.unlock(required(opts, "wallet-id"), passphrase(opts));
        WalletBalance balance = session.balance();
        print("walletId", session.profile().id(),
                "lovelace", balance.lovelace(),
                "ada", new BigDecimal(balance.lovelace()).movePointLeft(6).toPlainString(),
                "utxoCount", balance.utxoCount(),
                "addressesScanned", balance.addressCount(),
                "assets", balance.assets().size());
    }

    private static void send(FileStoredWalletRepository repository, WalletNetwork network,
                             String baseUrl, Map<String, String> opts) {
        WalletService service = walletService(repository, network, baseUrl, opts);
        WalletService.Session session = service.unlock(required(opts, "wallet-id"), passphrase(opts));
        String to = required(opts, "to");
        BigInteger lovelace;
        try {
            lovelace = new BigDecimal(required(opts, "ada")).movePointRight(6).toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ProbeException("Invalid --ada amount (max 6 decimal places): " + opts.get("ada"));
        }

        QuickAdaTxDraft draft = session.draftPayment(to, lovelace, List.of(), opts.get("message"));
        String txHash;
        try {
            txHash = session.submit(draft);
        } catch (WalletService.WalletServiceException e) {
            throw new ProbeException(e.getMessage());
        }

        boolean confirmed = false;
        if (opts.containsKey("wait")) {
            confirmed = service.awaitConfirmation(txHash,
                    Long.parseLong(opts.getOrDefault("wait-seconds", "60")));
        }
        print("txHash", txHash,
                "fee", draft.fee(),
                "lovelace", draft.lovelace(),
                "to", draft.toAddress(),
                "submitted", true,
                "confirmed", confirmed);
        if (opts.containsKey("wait") && !confirmed) {
            throw new ProbeException("Transaction not confirmed within wait window: " + txHash);
        }
    }

    private static void delegate(FileStoredWalletRepository repository, WalletNetwork network,
                                 String baseUrl, Map<String, String> opts) {
        WalletService service = walletService(repository, network, baseUrl, opts);
        WalletService.Session session = service.unlock(required(opts, "wallet-id"), passphrase(opts));
        QuickAdaTxDraft draft = session.draftDelegation(required(opts, "pool-id"));
        String txHash = session.submit(draft);
        boolean confirmed = opts.containsKey("wait") && service.awaitConfirmation(txHash, 60);
        print("txHash", txHash, "fee", draft.fee(), "action", draft.toAddress(),
                "submitted", true, "confirmed", confirmed);
    }

    private static void withdraw(FileStoredWalletRepository repository, WalletNetwork network,
                                 String baseUrl, Map<String, String> opts) {
        WalletService service = walletService(repository, network, baseUrl, opts);
        WalletService.Session session = service.unlock(required(opts, "wallet-id"), passphrase(opts));
        QuickAdaTxDraft draft = session.draftWithdrawal();
        String txHash = session.submit(draft);
        boolean confirmed = opts.containsKey("wait") && service.awaitConfirmation(txHash, 60);
        print("txHash", txHash, "fee", draft.fee(), "action", draft.toAddress(),
                "submitted", true, "confirmed", confirmed);
    }

    private static void mint(FileStoredWalletRepository repository, WalletNetwork network,
                             String baseUrl, Map<String, String> opts) {
        WalletService service = walletService(repository, network, baseUrl, opts);
        WalletService.Session session = service.unlock(required(opts, "wallet-id"), passphrase(opts));
        QuickAdaTxDraft draft = session.draftMint(required(opts, "asset"),
                new BigInteger(required(opts, "quantity")));
        String txHash = session.submit(draft);
        boolean confirmed = opts.containsKey("wait") && service.awaitConfirmation(txHash, 60);
        print("txHash", txHash, "fee", draft.fee(), "action", draft.toAddress(),
                "submitted", true, "confirmed", confirmed);
    }

    private static Path dataDirOf(Map<String, String> opts, WalletNetwork network) {
        return Paths.get(opts.getOrDefault("data-dir",
                System.getProperty("user.home") + "/.yano-wallet")).resolve(network.id());
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new ProbeException("Unexpected argument: " + arg);
            }
            int eq = arg.indexOf('=');
            if (eq < 0) {
                opts.put(arg.substring(2), "");
            } else {
                opts.put(arg.substring(2, eq), arg.substring(eq + 1));
            }
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new ProbeException("Missing required option --" + key);
        }
        return value;
    }

    /**
     * Mnemonic from --mnemonic, or --mnemonic-file (preferred for scripts:
     * spaces survive and the phrase stays out of process args/history).
     */
    private static String mnemonic(Map<String, String> opts) {
        String inline = opts.get("mnemonic");
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String file = opts.get("mnemonic-file");
        if (file != null && !file.isBlank()) {
            try {
                return java.nio.file.Files.readString(Paths.get(file)).trim();
            } catch (java.io.IOException e) {
                throw new ProbeException("Unable to read mnemonic file: " + file);
            }
        }
        throw new ProbeException("Missing required option --mnemonic or --mnemonic-file");
    }

    private static char[] passphrase(Map<String, String> opts) {
        String fromOpt = opts.get("passphrase");
        if (fromOpt != null && !fromOpt.isBlank()) {
            return fromOpt.toCharArray();
        }
        String fromEnv = System.getenv("YANO_WALLET_PASSPHRASE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.toCharArray();
        }
        throw new ProbeException("Passphrase required (--passphrase or YANO_WALLET_PASSPHRASE)");
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static void print(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        try {
            System.out.println(JSON.writeValueAsString(map));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to render probe output", e);
        }
    }

    private static final class ProbeException extends RuntimeException {
        ProbeException(String message) {
            super(message);
        }
    }

    private WalletProbe() {
    }
}
