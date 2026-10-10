package com.miqa.store.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** Independent from the outbound ERP key and the request-export credential. */
@Component
public final class ErpWebhookProperties {
    private final boolean receiveEnabled;
    private final boolean processingEnabled;
    private final byte[] secret;
    private final int timestampToleranceSeconds;
    private final int coalesceSeconds;
    private final int batchSize;
    private final int initialBackoffSeconds;
    private final int maxBackoffSeconds;
    private final int backoffSteps;

    public ErpWebhookProperties(
            @Value("${app.erp.webhook.receive-enabled:false}") boolean receiveEnabled,
            @Value("${app.erp.webhook.processing-enabled:false}") boolean processingEnabled,
            @Value("${app.erp.webhook.secret:}") String secret,
            @Value("${app.erp.webhook.timestamp-tolerance-seconds:300}") int tolerance,
            @Value("${app.erp.webhook.queue-interval-ms:5000}") int queueInterval,
            @Value("${app.erp.webhook.coalesce-seconds:3}") int coalesce,
            @Value("${app.erp.webhook.batch-size:200}") int batchSize,
            @Value("${app.erp.webhook.initial-backoff-seconds:30}") int initialBackoff,
            @Value("${app.erp.webhook.max-backoff-seconds:900}") int maxBackoff,
            @Value("${app.erp.webhook.backoff-steps:6}") int backoffSteps) {
        if ((receiveEnabled || !secret.isEmpty()) && !secret.matches("[!-~]{32,256}"))
            throw new IllegalStateException("Invalid webhook authentication configuration");
        if (tolerance < 1 || tolerance > 900 || queueInterval < 1000 || queueInterval > 3600000
                || coalesce < 0 || coalesce > 60
                || batchSize < 1 || batchSize > 1000 || initialBackoff < 1
                || maxBackoff < initialBackoff || maxBackoff > 86400
                || backoffSteps < 1 || backoffSteps > 20)
            throw new IllegalStateException("Invalid webhook processing configuration");
        this.receiveEnabled = receiveEnabled;
        this.processingEnabled = processingEnabled;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        timestampToleranceSeconds = tolerance;
        coalesceSeconds = coalesce;
        this.batchSize = batchSize;
        initialBackoffSeconds = initialBackoff;
        maxBackoffSeconds = maxBackoff;
        this.backoffSteps = backoffSteps;
    }
    public boolean receiveEnabled() { return receiveEnabled; }
    public boolean processingEnabled() { return processingEnabled; }
    byte[] secret() { return secret.clone(); }
    public int timestampToleranceSeconds() { return timestampToleranceSeconds; }
    public int coalesceSeconds() { return coalesceSeconds; }
    public int batchSize() { return batchSize; }
    public int initialBackoffSeconds() { return initialBackoffSeconds; }
    public int retryDelay(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), backoffSteps - 1);
        return (int) Math.min(maxBackoffSeconds, (long) initialBackoffSeconds << exponent);
    }
    @Override public String toString() { return "ErpWebhookProperties[REDACTED]"; }
}
