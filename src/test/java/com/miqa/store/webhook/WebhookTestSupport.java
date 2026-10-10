package com.miqa.store.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class WebhookTestSupport {
    static final String SECRET = "synthetic-webhook-test-secret-00000000000000000000";
    static final String ID = "39d45f22-0ea9-4e8f-9c27-a04a76360141";
    static final String BODY = "{\"eventId\":\"" + ID + "\",\"eventType\":\"ERP_CATALOG_CHANGED\","
            + "\"occurredAt\":\"2026-09-01T10:00:00Z\",\"schemaVersion\":1}";
    static ErpWebhookProperties properties(boolean receive, boolean process) {
        return new ErpWebhookProperties(receive, process, receive ? SECRET : "", 300, 5000, 3, 200, 30, 900, 6);
    }
    // Independent sender implementation; does not invoke the receiving filter's sign method.
    static String signature(String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '\n');
        return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
