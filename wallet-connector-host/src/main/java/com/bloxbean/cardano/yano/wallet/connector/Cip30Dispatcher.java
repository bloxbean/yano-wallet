package com.bloxbean.cardano.yano.wallet.connector;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a CIP-30 method call (name + params + page origin) onto the wallet SPI and
 * the codec, applying the consent rules: {@code isEnabled}/{@code enable} are
 * always allowed; everything else requires the wallet to be ready AND the origin
 * to be on the allowlist. Returns a JSON-serializable result or throws
 * {@link Cip30Exception}.
 */
public final class Cip30Dispatcher {

    private static final BigInteger DEFAULT_COLLATERAL = BigInteger.valueOf(5_000_000);

    private final Cip30Wallet wallet;
    private final Cip30Approvals approvals;

    public Cip30Dispatcher(Cip30Wallet wallet, Cip30Approvals approvals) {
        this.wallet = wallet;
        this.approvals = approvals;
    }

    public Object handle(String method, JsonNode params, String origin) {
        if (method == null) {
            throw Cip30Exception.invalid("Missing method");
        }
        switch (method) {
            case "isEnabled":
                return approvals.isConnected(origin);
            case "enable":
                return approvals.isConnected(origin) || approvals.confirmConnect(origin);
            case "getExtensions":
                return List.of(); // CIP-95 advertised here in a later milestone

            case "getNetworkId":
                requireEnabled(origin);
                return wallet.networkId();
            case "getUsedAddresses":
                requireEnabled(origin);
                return addressHexes(wallet.usedAddresses());
            case "getUnusedAddresses":
                requireEnabled(origin);
                return addressHexes(wallet.unusedAddresses());
            case "getChangeAddress":
                requireEnabled(origin);
                return Cip30Codec.addressHex(wallet.changeAddress());
            case "getRewardAddresses":
                requireEnabled(origin);
                return addressHexes(wallet.rewardAddresses());
            case "getBalance":
                requireEnabled(origin);
                return Cip30Codec.balanceHex(wallet.utxos());
            case "getUtxos":
                requireEnabled(origin);
                return utxos(params);
            case "getCollateral":
                requireEnabled(origin);
                return collateral(params);

            case "signTx":
                requireEnabled(origin);
                return signTx(params, origin);
            case "signData":
                requireEnabled(origin);
                return signData(params, origin);
            case "submitTx":
                requireEnabled(origin);
                return wallet.submitTx(textParam(params, "tx"));

            default:
                throw Cip30Exception.invalid("Unknown method: " + method);
        }
    }

    private void requireEnabled(String origin) {
        if (!wallet.isReady()) {
            throw Cip30Exception.internal("Yano wallet is locked or not connected.");
        }
        if (!approvals.isConnected(origin)) {
            throw Cip30Exception.refused("This site is not connected. Call enable() first.");
        }
    }

    private static List<String> addressHexes(List<String> bech32) {
        List<String> out = new ArrayList<>(bech32.size());
        for (String a : bech32) {
            out.add(Cip30Codec.addressHex(a));
        }
        return out;
    }

    // getUtxos(amount?, paginate?): amount-based filtering is deferred; supports paginate {page,limit}.
    private List<String> utxos(JsonNode params) {
        List<Utxo> all = wallet.utxos();
        JsonNode paginate = params == null ? null : params.get("paginate");
        if (paginate != null && paginate.hasNonNull("page") && paginate.hasNonNull("limit")) {
            int page = paginate.get("page").asInt();
            int limit = Math.max(1, paginate.get("limit").asInt());
            int from = page * limit;
            if (from >= all.size()) {
                return List.of();
            }
            all = all.subList(from, Math.min(from + limit, all.size()));
        }
        List<String> out = new ArrayList<>(all.size());
        for (Utxo u : all) {
            out.add(Cip30Codec.utxoHex(u));
        }
        return out;
    }

    // getCollateral({amount}): pure-ADA UTxOs summing to >= the requested lovelace (default 5 ADA).
    private List<String> collateral(JsonNode params) {
        BigInteger required = DEFAULT_COLLATERAL;
        JsonNode p = params == null ? null : params.get("params");
        if (p != null && p.hasNonNull("amount") && p.get("amount").isNumber()) {
            required = BigInteger.valueOf(p.get("amount").asLong());
        }
        List<String> chosen = new ArrayList<>();
        BigInteger sum = BigInteger.ZERO;
        for (Utxo u : wallet.utxos()) {
            if (!Cip30Codec.isPureAda(u)) {
                continue;
            }
            chosen.add(Cip30Codec.utxoHex(u));
            sum = sum.add(Cip30Codec.lovelace(u));
            if (sum.compareTo(required) >= 0) {
                return chosen;
            }
        }
        return chosen.isEmpty() ? null : chosen; // null = no suitable collateral (CIP-30 allows null)
    }

    private String signTx(JsonNode params, String origin) {
        String txHex = textParam(params, "tx");
        boolean partial = params != null && params.path("partialSign").asBoolean(false);
        // The exact CBOR the dApp asked to have signed goes to the consent gate,
        // which simulates it (ADR-042). Summarising here would both duplicate that
        // work and risk describing something other than what gets signed.
        if (!approvals.confirmSign(origin, txHex, partial)) {
            throw Cip30Exception.refused("The user declined to sign.");
        }
        return wallet.signTx(txHex, partial);
    }

    private Object signData(JsonNode params, String origin) {
        String addr = textParam(params, "addr");
        String payload = textParam(params, "payload");
        if (!approvals.confirmSignData(origin, addr)) {
            throw Cip30Exception.refused("The user declined to sign.");
        }
        Cip30Wallet.DataSignature sig = wallet.signData(addr, payload);
        return java.util.Map.of("signature", sig.signature(), "key", sig.key());
    }

    private static String textParam(JsonNode params, String name) {
        if (params == null || !params.hasNonNull(name)) {
            throw Cip30Exception.invalid("Missing parameter: " + name);
        }
        return params.get(name).asText();
    }

}
