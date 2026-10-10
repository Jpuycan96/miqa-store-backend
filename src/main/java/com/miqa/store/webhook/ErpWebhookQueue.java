package com.miqa.store.webhook;

import com.miqa.store.admin.AdminFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@Repository
public class ErpWebhookQueue {
    // Separate session-level worker lock; synchronize() still owns its original transaction lock.
    static final long WORKER_LOCK = 724193820128L;
    public record Batch(UUID token, int size, int attempts) {}
    public record FailedEvent(UUID eventId, java.time.Instant receivedAt, int attempts, String lastOutcome) {}
    public record RequeueReceipt(UUID requestId, UUID eventId, java.time.Instant requeuedAt,
                                 java.time.Instant nextAttemptAt, String status) {}
    private record RecoveryAudit(RequeueReceipt receipt, String adminId, String reason) {}
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final ErpWebhookProperties properties;
    @Autowired
    public ErpWebhookQueue(DataSource dataSource, ErpWebhookProperties properties) {
        this(dataSource, properties, new JdbcTemplate(dataSource), new JdbcTransactionManager(dataSource));
    }
    ErpWebhookQueue(DataSource dataSource, ErpWebhookProperties properties, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.dataSource = dataSource; this.jdbc = jdbc; this.properties = properties;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setTimeout(10);
    }
    public boolean accept(ErpWebhookReceiver.Event event) {
        return Boolean.TRUE.equals(write.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO erp_catalog_webhook_events(event_id,event_type,schema_version,occurred_at,body_sha256,next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, current_timestamp + (? * interval '1 second'))
                    ON CONFLICT (event_id) DO NOTHING
                    """, event.eventId(), event.eventType(), event.schemaVersion(), Timestamp.from(event.occurredAt()),
                    event.bodyHash(), properties.coalesceSeconds());
            if (inserted == 0) {
                String hash = jdbc.queryForObject("SELECT body_sha256 FROM erp_catalog_webhook_events WHERE event_id = ?",
                        String.class, event.eventId());
                if (!event.bodyHash().equals(hash)) throw new ErpWebhookFailure(409, "EVENT_ID_CONFLICT");
            }
            return inserted == 1;
        }));
    }

    public List<FailedEvent> failed(int limit) {
        if (limit < 1 || limit > 100) throw new AdminFailure(400, "El limite debe estar entre 1 y 100");
        return jdbc.query("""
                SELECT event_id,received_at,attempts,last_outcome FROM erp_catalog_webhook_events
                WHERE state='FAILED' ORDER BY received_at,event_id LIMIT ?
                """, (rs, row) -> new FailedEvent(rs.getObject("event_id", UUID.class),
                rs.getTimestamp("received_at").toInstant(), rs.getInt("attempts"), rs.getString("last_outcome")), limit);
    }

    /** Lock the original receipt, audit and requeue in one short independent transaction.
     * Replaying a request returns its first receipt even if the worker has already finished.
     * Neither attempts nor the last diagnostic outcome are erased; no ERP HTTP here. */
    public RequeueReceipt requeue(UUID eventId, UUID requestId, String adminId, String reason) {
        if (eventId == null || requestId == null || adminId == null || adminId.isBlank() || adminId.length() > 64
                || reason == null || reason.strip().codePointCount(0, reason.strip().length()) < 8 || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl))
            throw new AdminFailure(400, "Solicitud de recuperacion invalida");
        String normalizedReason = reason.strip();
        try {
            return Objects.requireNonNull(write.execute(transaction -> {
                var states = jdbc.query("SELECT state FROM erp_catalog_webhook_events WHERE event_id=? FOR UPDATE",
                        (rs, row) -> rs.getString(1), eventId);
                if (states.isEmpty()) throw new AdminFailure(404, "Evento no encontrado");
                var previous = jdbc.query("""
                        SELECT request_id,event_id,admin_id,reason,requeued_at,next_attempt_at
                        FROM erp_catalog_webhook_requeues WHERE request_id=?
                        """, (rs, row) -> new RecoveryAudit(requeueReceipt(rs), rs.getString("admin_id"), rs.getString("reason")), requestId);
                if (!previous.isEmpty()) {
                    var audit = previous.getFirst();
                    if (!audit.receipt().eventId().equals(eventId) || !audit.adminId().equals(adminId)
                            || !audit.reason().equals(normalizedReason))
                        throw new AdminFailure(409, "La solicitud de recuperacion ya se utilizo con otros datos");
                    return audit.receipt();
                }
                if (!"FAILED".equals(states.getFirst())) throw new AdminFailure(409, "Solo se pueden recuperar eventos FAILED");
                // A unique request_id also arbitrates simultaneous reuse across different events.
                // Any insert/update/commit failure rolls back both the audit and the queue change.
                var receipt = jdbc.query("""
                        INSERT INTO erp_catalog_webhook_requeues
                          (request_id,event_id,admin_id,reason,previous_attempts,previous_outcome,next_attempt_at)
                        SELECT ?,event_id,?,?,attempts,last_outcome,current_timestamp + (? * interval '1 second')
                        FROM erp_catalog_webhook_events WHERE event_id=? AND state='FAILED'
                        RETURNING request_id,event_id,requeued_at,next_attempt_at
                        """, (rs, row) -> requeueReceipt(rs), requestId, adminId, normalizedReason,
                        properties.coalesceSeconds(), eventId).getFirst();
                int updated = jdbc.update("""
                        UPDATE erp_catalog_webhook_events SET state='PENDING',next_attempt_at=?
                        WHERE event_id=? AND state='FAILED'
                        """, Timestamp.from(receipt.nextAttemptAt()), eventId);
                if (updated != 1) throw new AdminFailure(409, "El evento cambio durante la recuperacion");
                return receipt;
            }));
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // A cross-event request ID race must not expose SQL or the generic catalog slug error.
            throw new AdminFailure(409, "La solicitud de recuperacion ya se utilizo con otros datos");
        }
    }

    private static RequeueReceipt requeueReceipt(ResultSet rs) throws SQLException {
        return new RequeueReceipt(rs.getObject("request_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getTimestamp("requeued_at").toInstant(), rs.getTimestamp("next_attempt_at").toInstant(), "REQUEUED");
    }
    /** Dedicated connection, never a transaction spanning queue writes, ERP and acknowledgement.
     * Pool must have at least two connections. Always unlock before returning the session to it. */
    public boolean withWorkerLock(Runnable work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            boolean acquired = false;
            try {
                try {
                    acquired = lock(connection, "SELECT pg_try_advisory_lock(?)");
                } catch (SQLException ex) {
                    // The server may have acquired the lock before a transport failure.
                    connection.abort(Runnable::run);
                    throw ex;
                }
                if (!acquired) return false;
                work.run();
                return true;
            } finally {
                if (acquired) {
                    try {
                        if (!lock(connection, "SELECT pg_advisory_unlock(?)"))
                            throw new SQLException("Worker lock was lost");
                    } catch (SQLException ex) {
                        // Do not leave a session lock in the pool if explicit release failed.
                        connection.abort(Runnable::run);
                        throw ex;
                    }
                }
            }
        } catch (SQLException ex) {
            // SQL/transport exceptions can include infrastructure details; never retain their cause.
            throw new IllegalStateException("Webhook worker connection unavailable");
        }
    }
    private static boolean lock(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, WORKER_LOCK);
            try (var result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }
    /** Only the holder of WORKER_LOCK calls these processing methods. No lease can expire mid-HTTP.
     * A restarted worker recovers PROCESSING rows left by a dead session before selecting new work. */
    public void recoverInterrupted() {
        write.executeWithoutResult(status -> jdbc.update("""
                UPDATE erp_catalog_webhook_events SET state='RETRY', processing_token=NULL, processing_started_at=NULL,
                    next_attempt_at=current_timestamp + (? * interval '1 second'), last_outcome='WORKER_RECOVERED'
                WHERE state='PROCESSING'
                """, properties.initialBackoffSeconds()));
    }
    public Optional<Batch> claim() {
        return Objects.requireNonNull(write.execute(status -> {
            UUID token = UUID.randomUUID();
            // All notifications request the same full reconciliation. A retry cooldown gates new
            // notifications too, so continuous ERP events cannot bypass outage backoff.
            // Only this captured batch can be acknowledged; arrivals during sync stay pending.
            List<Integer> attempts = jdbc.query("""
                    WITH selected AS (
                      SELECT event_id FROM erp_catalog_webhook_events
                      WHERE state IN ('PENDING','RETRY') AND EXISTS (
                        SELECT 1 FROM erp_catalog_webhook_events WHERE state IN ('PENDING','RETRY')
                          AND next_attempt_at <= current_timestamp)
                        AND NOT EXISTS (SELECT 1 FROM erp_catalog_webhook_events
                          WHERE state='RETRY' AND next_attempt_at > current_timestamp)
                      ORDER BY received_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    UPDATE erp_catalog_webhook_events e SET state='PROCESSING',processing_token=?,
                      processing_started_at=current_timestamp,next_attempt_at=NULL,
                      attempts=LEAST(e.attempts::bigint+1,2147483647)::integer
                    FROM selected s WHERE e.event_id=s.event_id RETURNING e.attempts
                    """, (rs, row) -> rs.getInt(1), properties.batchSize(), token);
            return attempts.isEmpty() ? Optional.empty() : Optional.of(new Batch(token, attempts.size(),
                    attempts.stream().mapToInt(Integer::intValue).max().orElseThrow()));
        }));
    }
    public void complete(Batch batch) {
        write.executeWithoutResult(status -> jdbc.update("""
                UPDATE erp_catalog_webhook_events SET state='PROCESSED', processed_at=current_timestamp,
                    last_outcome='SUCCESS',processing_token=NULL,processing_started_at=NULL
                WHERE processing_token=? AND state='PROCESSING'
                """, batch.token()));
    }
    public void retry(Batch batch, String outcome, int delaySeconds) { finish(batch, "RETRY", outcome, delaySeconds); }
    public void fail(Batch batch, String outcome) { finish(batch, "FAILED", outcome, 0); }
    private void finish(Batch batch, String state, String outcome, int delaySeconds) {
        if (!Set.of("ERP_ERROR","NOT_CONFIGURED","INVALID_CONTRACT","SYNC_BUSY","WORKER_ERROR").contains(outcome))
            throw new IllegalArgumentException("Unknown worker outcome");
        write.executeWithoutResult(status -> jdbc.update("""
                UPDATE erp_catalog_webhook_events SET state=?,last_outcome=?,processing_token=NULL,processing_started_at=NULL,
                    next_attempt_at=CASE WHEN ?='RETRY' THEN current_timestamp + (? * interval '1 second') ELSE NULL END
                WHERE processing_token=? AND state='PROCESSING'
                """, state, outcome, state, delaySeconds, batch.token()));
    }
}
