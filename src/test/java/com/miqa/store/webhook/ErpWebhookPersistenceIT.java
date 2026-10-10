package com.miqa.store.webhook;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in only, after the owner applies V11 in the isolated TEST DB. Never starts Boot/Flyway or runs DDL.
 * Real independent connections and commits; only this test's UUID fixtures are removed afterward.
 * Requires an empty webhook queue, so no other work is processed by these tests. */
@EnabledIfEnvironmentVariable(named="RUN_ERP_WEBHOOK_PERSISTENCE_IT", matches="true")
class ErpWebhookPersistenceIT {
    private DataSource source;
    private JdbcTemplate jdbc;
    private ErpWebhookQueue queue;
    private final Set<UUID> fixtures = new HashSet<>();
    @BeforeEach void setup() {
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db", "miqa_store_local", password);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("miqa_store_test_db");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_webhook_events",Integer.class))
                .as("Requires isolated empty TEST webhook queue with V11 already applied").isZero();
        queue = new ErpWebhookQueue(source,properties(true,true));
    }
    @AfterEach void cleanup() {
        if (jdbc != null) for (UUID id:fixtures)
            jdbc.update("DELETE FROM erp_catalog_webhook_events WHERE event_id=?",id);
    }
    private ErpWebhookReceiver.Event event() {
        UUID id=UUID.randomUUID(); fixtures.add(id);
        return new ErpWebhookReceiver.Event(id,"ERP_CATALOG_CHANGED",Instant.parse("2026-09-01T10:00:00Z"),1,"a".repeat(64));
    }
    private void due(UUID id) {
        jdbc.update("UPDATE erp_catalog_webhook_events SET next_attempt_at=current_timestamp - interval '1 second' WHERE event_id=?",id);
    }
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM erp_catalog_webhook_events WHERE event_id=?",String.class,id); }
    @Test void receiptIsVisibleOnAnotherConnectionAndDuplicateCannotReschedule() {
        var event=event(); assertThat(queue.accept(event)).isTrue();
        JdbcTemplate other=new JdbcTemplate(source);
        assertThat(other.queryForObject("SELECT state FROM erp_catalog_webhook_events WHERE event_id=?",String.class,event.eventId()))
                .isEqualTo("PENDING");
        Object next=other.queryForObject("SELECT next_attempt_at FROM erp_catalog_webhook_events WHERE event_id=?",Timestamp.class,event.eventId());
        assertThat(queue.accept(event)).isFalse();
        assertThat(other.queryForObject("SELECT next_attempt_at FROM erp_catalog_webhook_events WHERE event_id=?",Timestamp.class,event.eventId()))
                .isEqualTo(next);
        var conflicting=new ErpWebhookReceiver.Event(event.eventId(),event.eventType(),event.occurredAt(),1,"b".repeat(64));
        assertThatThrownBy(() -> queue.accept(conflicting)).isInstanceOf(ErpWebhookFailure.class);
        assertThat(other.queryForObject("SELECT count(*) FROM erp_catalog_webhook_events",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentDeliveryOfSameEventCreatesOneDurableReceipt() throws Exception {
        var event=event(); var other=new ErpWebhookQueue(source,properties(true,true));
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var first=executor.submit(() -> { start.await(); return queue.accept(event); });
            var second=executor.submit(() -> { start.await(); return other.accept(event); }); start.countDown();
            assertThat(List.of(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_webhook_events",Integer.class)).isEqualTo(1);
    }
    @Test void invalidInsertRollsBackWithoutReceipt() {
        var event=event(); var invalid=new ErpWebhookReceiver.Event(event.eventId(),event.eventType(),event.occurredAt(),1,"invalid");
        assertThatThrownBy(() -> queue.accept(invalid)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_webhook_events",Integer.class)).isZero();
    }
    @Test void batchCoalescesAndAcknowledgesOnlyClaimedEventsWhileNewNotificationStaysPending() {
        var first=event(); var second=event(); var later=event(); queue.accept(first); queue.accept(second);
        queue.withWorkerLock(() -> {
            assertThat(queue.claim()).isEmpty(); due(first.eventId());
            var batch=queue.claim().orElseThrow(); assertThat(batch.size()).isEqualTo(2); assertThat(batch.attempts()).isEqualTo(1);
            queue.accept(later); queue.complete(batch);
        });
        assertThat(state(first.eventId())).isEqualTo("PROCESSED"); assertThat(state(second.eventId())).isEqualTo("PROCESSED");
        assertThat(state(later.eventId())).isEqualTo("PENDING");
    }
    @Test void restartRecoversInterruptedClaimAndOldTokenCannotAcknowledgeIt() {
        var event=event(); queue.accept(event); due(event.eventId());
        var captured=new java.util.concurrent.atomic.AtomicReference<ErpWebhookQueue.Batch>();
        queue.withWorkerLock(() -> captured.set(queue.claim().orElseThrow()));
        assertThat(state(event.eventId())).isEqualTo("PROCESSING");
        var restarted=new ErpWebhookQueue(source,properties(true,true));
        restarted.withWorkerLock(() -> {
            restarted.recoverInterrupted(); assertThat(restarted.claim()).isEmpty();
            queue.complete(captured.get()); assertThat(state(event.eventId())).isEqualTo("RETRY");
            due(event.eventId()); var retry=restarted.claim().orElseThrow(); assertThat(retry.attempts()).isEqualTo(2);
            restarted.retry(retry,"ERP_ERROR",30); assertThat(restarted.claim()).isEmpty();
        });
        assertThat(jdbc.queryForObject("SELECT last_outcome FROM erp_catalog_webhook_events WHERE event_id=?",String.class,event.eventId()))
                .isEqualTo("ERP_ERROR");
    }
    @Test void newlyDueNotificationCannotBypassRetryBackoff() {
        var first=event(); queue.accept(first); due(first.eventId());
        queue.withWorkerLock(() -> {
            var batch=queue.claim().orElseThrow(); queue.retry(batch,"ERP_ERROR",30);
            var later=event(); queue.accept(later); due(later.eventId());
            assertThat(queue.claim()).isEmpty();
            due(first.eventId()); var retry=queue.claim().orElseThrow(); assertThat(retry.size()).isEqualTo(2);
            queue.complete(retry);
        });
    }
    @Test void independentWorkerSessionsExcludeEachOtherAndUnlockBeforePoolReturn() {
        var other=new ErpWebhookQueue(source,properties(true,true));
        assertThat(queue.withWorkerLock(() -> assertThat(other.withWorkerLock(() -> fail("Second worker must not run"))).isFalse())).isTrue();
        assertThat(other.withWorkerLock(() -> {})).isTrue();
        assertThatThrownBy(() -> queue.withWorkerLock(() -> { throw new IllegalStateException("synthetic interruption"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(other.withWorkerLock(() -> {})).isTrue();
        queue.withWorkerLock(() -> {
            // The preexisting synchronizer lock remains independently usable in its own transaction.
            try (Connection connection=source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement=connection.createStatement(); var result=statement.executeQuery("SELECT pg_try_advisory_xact_lock(724193820126)")) {
                    assertThat(result.next()).isTrue(); assertThat(result.getBoolean(1)).isTrue();
                } finally { connection.rollback(); }
            } catch (SQLException ex) { throw new IllegalStateException("TEST lock check failed"); }
        });
    }
}
