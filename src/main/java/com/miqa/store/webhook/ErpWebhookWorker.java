package com.miqa.store.webhook;

import com.miqa.store.admin.AdminFailure;
import com.miqa.store.erp.ErpCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!admin-bootstrap")
@ConditionalOnProperty(name="app.erp.webhook.processing-enabled", havingValue="true")
public class ErpWebhookWorker {
    private static final Logger log = LoggerFactory.getLogger(ErpWebhookWorker.class);
    private final ErpWebhookProperties properties;
    private final ErpWebhookQueue queue;
    private final ErpCatalogService synchronizer;
    public ErpWebhookWorker(ErpWebhookProperties properties, ErpWebhookQueue queue, ErpCatalogService synchronizer) {
        this.properties=properties; this.queue=queue; this.synchronizer=synchronizer;
    }
    @Scheduled(fixedDelayString="${app.erp.webhook.queue-interval-ms:5000}", initialDelayString="${app.erp.webhook.queue-interval-ms:5000}")
    public void tick() {
        if (!properties.processingEnabled()) return;
        try {
            queue.withWorkerLock(() -> {
                queue.recoverInterrupted();
                queue.claim().ifPresent(this::process);
            });
        } catch (RuntimeException ex) {
            // An unfinished batch remains durable and is recovered after the lock is released.
            log.warn("ERP webhook worker deferred; exception type: {}", ex.getClass().getSimpleName());
        }
    }
    private void process(ErpWebhookQueue.Batch batch) {
        String outcome;
        try { outcome = synchronizer.synchronize().outcome(); }
        catch (AdminFailure ex) { outcome = ex.status() == 409 ? "SYNC_BUSY" : "WORKER_ERROR"; }
        catch (RuntimeException ex) { outcome = "WORKER_ERROR"; }
        switch (outcome) {
            case "SUCCESS" -> queue.complete(batch);
            case "NOT_CONFIGURED", "INVALID_CONTRACT" -> queue.fail(batch, outcome);
            default -> queue.retry(batch, "SYNC_BUSY".equals(outcome) ? "SYNC_BUSY"
                    : "ERP_ERROR".equals(outcome) ? "ERP_ERROR" : "WORKER_ERROR", properties.retryDelay(batch.attempts()));
        }
        log.info("ERP webhook batch finished; events={}, outcome={}", batch.size(),
                switch(outcome) { case "SUCCESS","NOT_CONFIGURED","INVALID_CONTRACT","SYNC_BUSY","ERP_ERROR" -> outcome; default -> "WORKER_ERROR"; });
    }
}
