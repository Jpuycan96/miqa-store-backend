package com.miqa.store.webhook;

import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.Instant;
import java.util.*;

@Service
public class ErpWebhookReceiver {
    public record Event(UUID eventId, String eventType, Instant occurredAt, int schemaVersion, String bodyHash) {}
    public record Receipt(int schemaVersion, UUID eventId, String status) {}
    private final ErpWebhookQueue queue;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ErpWebhookReceiver(ErpWebhookQueue queue) { this.queue = queue; }
    public Receipt receive(byte[] bytes) {
        Event event = decode(bytes);
        // accept returns only after its transaction commits; no ERP call in this request.
        boolean inserted = queue.accept(event);
        return new Receipt(1, event.eventId(), inserted ? "ACCEPTED" : "DUPLICATE");
    }
    Event decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length > ErpWebhookAuthenticationFilter.MAX_BODY_BYTES) throw new IllegalArgumentException();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var node = mapper.readTree(json);
            if (!node.isObject() || node.size() != 4
                    || !node.has("eventId") || !node.get("eventId").isString()
                    || !node.get("eventId").asString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !node.has("eventType") || !node.get("eventType").isString()
                    || !"ERP_CATALOG_CHANGED".equals(node.get("eventType").asString())
                    || !node.has("schemaVersion") || !node.get("schemaVersion").isInt() || node.get("schemaVersion").asInt() != 1
                    || !node.has("occurredAt") || !node.get("occurredAt").isString()) throw new IllegalArgumentException();
            String timestamp = node.get("occurredAt").asString();
            if (!timestamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z")) throw new IllegalArgumentException();
            Instant occurredAt = Instant.parse(timestamp);
            if (occurredAt.isBefore(Instant.EPOCH) || occurredAt.isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException();
            return new Event(UUID.fromString(node.get("eventId").asString()), "ERP_CATALOG_CHANGED", occurredAt, 1,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Exception ex) { throw new ErpWebhookFailure(400, "INVALID_EVENT"); }
    }
}
