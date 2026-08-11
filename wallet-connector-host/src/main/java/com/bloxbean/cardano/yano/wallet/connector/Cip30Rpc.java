package com.bloxbean.cardano.yano.wallet.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transport-independent request envelope of the CIP-30 bridge (ADR-035):
 * a request is {@code {id, method, params, origin}}, the reply {@code {id,
 * result}} or {@code {id, error:{code, info}}}. Shared by the WebSocket server
 * and the Native Messaging socket server so both speak byte-identical JSON.
 */
final class Cip30Rpc {

    private static final Logger log = LoggerFactory.getLogger(Cip30Rpc.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Cip30Dispatcher dispatcher;

    Cip30Rpc(Cip30Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** Handles one request message, always returning a reply message. */
    String handle(String message) {
        String id = null;
        try {
            JsonNode req = mapper.readTree(message);
            id = req.path("id").asText(null);
            String method = req.path("method").asText(null);
            String origin = req.path("origin").asText("");
            JsonNode params = req.get("params");
            Object result = dispatcher.handle(method, params, origin);
            return ok(id, result);
        } catch (Cip30Exception e) {
            return error(id, e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("CIP-30 request failed: {}", e.getMessage());
            return error(id, Cip30Exception.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    private String ok(String id, Object result) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.set("result", mapper.valueToTree(result));
        return node.toString();
    }

    private String error(String id, int code, String info) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        ObjectNode err = node.putObject("error");
        err.put("code", code);
        err.put("info", info);
        return node.toString();
    }
}
