package com.miqa.store.webhook;

import com.miqa.store.admin.AdminFailure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prepared, opt-in SQL coverage: requires V12 applied by the owner and an empty isolated TEST queue.
 * No Boot/Flyway/DDL, never included by the default *Test selection. */
@EnabledIfEnvironmentVariable(named="RUN_ERP_WEBHOOK_RECOVERY_IT",matches="true")
class ErpWebhookRecoveryPostgresIT {
    private DataSource source;
    private JdbcTemplate jdbc;
    private ErpWebhookQueue queue;
    private final Set<UUID> fixtures = new HashSet<>();
    @BeforeEach void setup() {
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db","miqa_store_local",password);
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo("miqa_store_test_db");
        assertThat(jdbc.queryForObject("SELECT current_user",String.class)).isEqualTo("miqa_store_local");
        assertThat(jdbc.queryForObject("SELECT host(inet_server_addr())",String.class)).isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT inet_server_port()",Integer.class)).isEqualTo(55432);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='12' AND success",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_webhook_events",Integer.class)).isZero();
        queue = new ErpWebhookQueue(source,properties(true,true));
    }
    @AfterEach void cleanup() {
        if (jdbc != null) for (UUID event : fixtures) {
            jdbc.update("DELETE FROM erp_catalog_webhook_requeues WHERE event_id=?",event);
            jdbc.update("DELETE FROM erp_catalog_webhook_events WHERE event_id=?",event);
        }
    }
    private ErpWebhookReceiver.Event failedEvent() {
        UUID id = UUID.randomUUID(); fixtures.add(id);
        var event = new ErpWebhookReceiver.Event(id,"ERP_CATALOG_CHANGED",Instant.now(),1,"a".repeat(64));
        queue.accept(event);
        jdbc.update("UPDATE erp_catalog_webhook_events SET next_attempt_at=current_timestamp - interval '1 second' WHERE event_id=?",id);
        assertThat(queue.withWorkerLock(() -> queue.fail(queue.claim().orElseThrow(),"INVALID_CONTRACT"))).isTrue();
        return event;
    }
    private int audits(UUID id) { return jdbc.queryForObject("SELECT count(*) FROM erp_catalog_webhook_requeues WHERE event_id=?",Integer.class,id); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM erp_catalog_webhook_events WHERE event_id=?",String.class,id); }

    @Test void requeuePreservesReceiptHistoryAndWorkerProcessesItWithNextAttempt() {
        var event = failedEvent(); UUID request = UUID.randomUUID();
        var receipt = queue.requeue(event.eventId(),request,"synthetic-admin","Contrato corregido");
        assertThat(state(event.eventId())).isEqualTo("PENDING"); assertThat(audits(event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM erp_catalog_webhook_events WHERE event_id=?",Integer.class,event.eventId())).isEqualTo(1);
        assertThat(queue.accept(event)).isFalse(); // A sender replay cannot reschedule the recovery.
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM erp_catalog_webhook_events WHERE event_id=?",java.sql.Timestamp.class,event.eventId()).toInstant())
                .isEqualTo(receipt.nextAttemptAt());
        jdbc.update("UPDATE erp_catalog_webhook_events SET next_attempt_at=current_timestamp - interval '1 second' WHERE event_id=?",event.eventId());
        queue.withWorkerLock(() -> { var batch=queue.claim().orElseThrow(); assertThat(batch.attempts()).isEqualTo(2); queue.complete(batch); });
        assertThat(state(event.eventId())).isEqualTo("PROCESSED");
        assertThat(queue.requeue(event.eventId(),request,"synthetic-admin","Contrato corregido")).isEqualTo(receipt);
        assertThat(state(event.eventId())).isEqualTo("PROCESSED"); assertThat(audits(event.eventId())).isEqualTo(1);
    }
    @Test void concurrentSameRequestCommitsOnlyOneRecoveryAndAudit() throws Exception {
        var event=failedEvent(); UUID request=UUID.randomUUID(); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<ErpWebhookQueue.RequeueReceipt> work=() -> { start.await(); return new ErpWebhookQueue(source,properties(true,true))
                    .requeue(event.eventId(),request,"synthetic-admin","Contrato corregido"); };
            var first=executor.submit(work); var second=executor.submit(work); start.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(second.get(5,TimeUnit.SECONDS));
        }
        assertThat(audits(event.eventId())).isEqualTo(1); assertThat(state(event.eventId())).isEqualTo("PENDING");
    }
    @Test void simultaneousDifferentRequestsCannotRecoverSameFailedEventTwice() throws Exception {
        var event=failedEvent(); var start=new CountDownLatch(1);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> work=() -> {
                start.await();
                try { new ErpWebhookQueue(source,properties(true,true)).requeue(event.eventId(),UUID.randomUUID(),"synthetic-admin","Contrato corregido"); return 202; }
                catch (AdminFailure conflict) { return conflict.status(); }
            };
            var first=executor.submit(work); var second=executor.submit(work); start.countDown();
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,409);
        }
        assertThat(audits(event.eventId())).isEqualTo(1);
    }
    @Test void failureAfterAuditInsertRollsBackBothChanges() {
        var event=failedEvent();
        var failingJdbc=spy(new JdbcTemplate(source));
        doReturn(0).when(failingJdbc).update(contains("SET state='PENDING'"),any(),eq(event.eventId()));
        var failingQueue=new ErpWebhookQueue(source,properties(true,true),failingJdbc,new JdbcTransactionManager(source));
        assertThatThrownBy(() -> failingQueue.requeue(event.eventId(),UUID.randomUUID(),"synthetic-admin","Contrato corregido"))
                .isInstanceOf(AdminFailure.class);
        assertThat(audits(event.eventId())).isZero(); assertThat(state(event.eventId())).isEqualTo("FAILED");
    }
    @Test void reusedRequestForAnotherEventCannotChangeEitherReceipt() {
        var first=failedEvent(); var second=failedEvent(); UUID request=UUID.randomUUID();
        queue.requeue(first.eventId(),request,"synthetic-admin","Contrato corregido");
        assertThatThrownBy(() -> queue.requeue(second.eventId(),request,"synthetic-admin","Contrato corregido"))
                .isInstanceOf(AdminFailure.class);
        assertThat(state(second.eventId())).isEqualTo("FAILED"); assertThat(audits(second.eventId())).isZero();
    }
    @Test void anotherPermanentFailureRequiresNewRecoveryRequestAndKeepsBothAuditEntries() {
        var event=failedEvent(); UUID firstRequest=UUID.randomUUID();
        var receipt=queue.requeue(event.eventId(),firstRequest,"synthetic-admin","Contrato corregido");
        jdbc.update("UPDATE erp_catalog_webhook_events SET next_attempt_at=current_timestamp - interval '1 second' WHERE event_id=?",event.eventId());
        queue.withWorkerLock(() -> queue.fail(queue.claim().orElseThrow(),"NOT_CONFIGURED"));
        assertThat(queue.requeue(event.eventId(),firstRequest,"synthetic-admin","Contrato corregido")).isEqualTo(receipt);
        assertThat(state(event.eventId())).isEqualTo("FAILED");
        queue.requeue(event.eventId(),UUID.randomUUID(),"synthetic-admin","Credencial reparada");
        assertThat(state(event.eventId())).isEqualTo("PENDING"); assertThat(audits(event.eventId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT max(previous_attempts) FROM erp_catalog_webhook_requeues WHERE event_id=?",Integer.class,event.eventId())).isEqualTo(2);
    }
}
