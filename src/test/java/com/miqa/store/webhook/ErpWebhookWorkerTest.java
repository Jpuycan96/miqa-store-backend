package com.miqa.store.webhook;

import com.miqa.store.admin.AdminFailure;
import com.miqa.store.erp.ErpCatalogDtos.SyncStatus;
import com.miqa.store.erp.ErpCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErpWebhookWorkerTest {
    /** Stateful unit double: storage survives worker replacement; time never sleeps. SQL has separate IT coverage. */
    static class StoredQueue extends ErpWebhookQueue {
        static class Row { String state="PENDING", outcome; int attempts; long next=3; UUID token; }
        final Map<UUID, Row> rows = new LinkedHashMap<>();
        final AtomicBoolean owner = new AtomicBoolean();
        long now;
        boolean failAcknowledgement;
        StoredQueue() { super(mock(DataSource.class), properties(true,true), mock(JdbcTemplate.class), mock(PlatformTransactionManager.class)); }
        UUID add() { UUID id=UUID.randomUUID(); Row row=new Row(); row.next=now+3; rows.put(id,row); return id; }
        @Override public boolean withWorkerLock(Runnable work) {
            if (!owner.compareAndSet(false,true)) return false;
            try { work.run(); return true; } finally { owner.set(false); }
        }
        @Override public void recoverInterrupted() {
            for (Row row:rows.values()) if (row.state.equals("PROCESSING")) {
                row.state="RETRY"; row.next=now+30; row.token=null; row.outcome="WORKER_RECOVERED";
            }
        }
        @Override public Optional<Batch> claim() {
            if (rows.values().stream().anyMatch(row -> row.state.equals("RETRY") && row.next>now)) return Optional.empty();
            boolean due=rows.values().stream().anyMatch(row -> Set.of("PENDING","RETRY").contains(row.state) && row.next<=now);
            if (!due) return Optional.empty();
            UUID token=UUID.randomUUID(); int size=0, attempts=0;
            for (Row row:rows.values()) if (Set.of("PENDING","RETRY").contains(row.state) && size<200) {
                row.state="PROCESSING"; row.token=token; row.attempts++; size++; attempts=Math.max(attempts,row.attempts);
            }
            return Optional.of(new Batch(token,size,attempts));
        }
        @Override public void complete(Batch batch) {
            if (failAcknowledgement) throw new IllegalStateException("synthetic ack outage");
            finish(batch,"PROCESSED","SUCCESS",0);
        }
        @Override public void retry(Batch batch,String outcome,int delay) { finish(batch,"RETRY",outcome,delay); }
        @Override public void fail(Batch batch,String outcome) { finish(batch,"FAILED",outcome,0); }
        private void finish(Batch batch,String state,String outcome,int delay) {
            rows.values().stream().filter(row -> batch.token().equals(row.token)).forEach(row -> {
                row.state=state; row.outcome=outcome; row.next=now+delay; row.token=null;
            });
        }
    }
    private final StoredQueue queue=new StoredQueue();
    private final ErpCatalogService synchronizer=mock(ErpCatalogService.class);
    private ErpWebhookWorker worker() { return new ErpWebhookWorker(properties(true,true),queue,synchronizer); }
    private SyncStatus status(String outcome) { return new SyncStatus(outcome,Instant.now(),null,0,0,0); }
    @Test void idleQueueAndDisabledProcessingNeverPollErp() {
        for (int i=0;i<10;i++) { queue.now+=5000; worker().tick(); }
        queue.add(); queue.now+=3;
        new ErpWebhookWorker(properties(true,false),queue,synchronizer).tick();
        verifyNoInteractions(synchronizer);
        assertThat(queue.rows.values()).allSatisfy(row -> assertThat(row.state).isEqualTo("PENDING"));
    }
    @Test void nearbyEventsWaitForCoalescingThenUseOneExistingSynchronizerCall() {
        queue.add(); queue.add(); queue.add(); when(synchronizer.synchronize()).thenReturn(status("SUCCESS"));
        worker().tick(); verifyNoInteractions(synchronizer);
        queue.now=3; worker().tick(); worker().tick();
        verify(synchronizer,times(1)).synchronize();
        assertThat(queue.rows.values()).allSatisfy(row -> {
            assertThat(row.state).isEqualTo("PROCESSED"); assertThat(row.attempts).isEqualTo(1);
        });
    }
    @Test void notificationsArrivingDuringSyncAreNotAcknowledgedByEarlierBatch() {
        UUID first=queue.add(); queue.now=3;
        when(synchronizer.synchronize()).thenAnswer(i -> { queue.add(); return status("SUCCESS"); });
        worker().tick();
        assertThat(queue.rows.get(first).state).isEqualTo("PROCESSED");
        assertThat(queue.rows.values().stream().filter(row -> row.state.equals("PENDING"))).hasSize(1);
        worker().tick(); verify(synchronizer,times(1)).synchronize();
        queue.now+=3; when(synchronizer.synchronize()).thenReturn(status("SUCCESS")); worker().tick();
        assertThat(queue.rows.values()).allSatisfy(row -> assertThat(row.state).isEqualTo("PROCESSED"));
        verify(synchronizer,times(2)).synchronize();
    }
    @Test void temporaryErpOutageBacksOffAndSurvivesWorkerRestartUntilRecovery() {
        UUID id=queue.add(); queue.now=3;
        when(synchronizer.synchronize()).thenReturn(status("ERP_ERROR"),status("ERP_ERROR"),status("SUCCESS"));
        worker().tick(); assertThat(queue.rows.get(id).next).isEqualTo(33);
        queue.now=32; worker().tick(); verify(synchronizer,times(1)).synchronize();
        queue.now=33; new ErpWebhookWorker(properties(true,true),queue,synchronizer).tick();
        assertThat(queue.rows.get(id).next).isEqualTo(93);
        queue.now=93; worker().tick(); assertThat(queue.rows.get(id).state).isEqualTo("PROCESSED");
        assertThat(queue.rows.get(id).attempts).isEqualTo(3);
    }
    @Test void newNotificationsCannotBypassCatalogRetryCooldown() {
        UUID first=queue.add(); queue.now=3;
        when(synchronizer.synchronize()).thenReturn(status("ERP_ERROR"),status("SUCCESS")); worker().tick();
        UUID later=queue.add(); queue.now=6; worker().tick();
        verify(synchronizer,times(1)).synchronize(); assertThat(queue.rows.get(later).state).isEqualTo("PENDING");
        queue.now=33; worker().tick();
        assertThat(queue.rows.get(first).state).isEqualTo("PROCESSED"); assertThat(queue.rows.get(later).state).isEqualTo("PROCESSED");
        verify(synchronizer,times(2)).synchronize();
    }
    @Test void interruptedBatchAndFailedAcknowledgementAreRecoveredWithDelay() {
        UUID id=queue.add(); queue.now=3;
        queue.withWorkerLock(() -> queue.claim().orElseThrow()); // previous process died after claiming
        worker().tick(); assertThat(queue.rows.get(id).state).isEqualTo("RETRY");
        assertThat(queue.rows.get(id).outcome).isEqualTo("WORKER_RECOVERED"); verifyNoInteractions(synchronizer);
        queue.now=33; when(synchronizer.synchronize()).thenReturn(status("SUCCESS")); queue.failAcknowledgement=true;
        worker().tick(); assertThat(queue.rows.get(id).state).isEqualTo("PROCESSING"); assertThat(queue.owner).isFalse();
        queue.failAcknowledgement=false; worker().tick();
        assertThat(queue.rows.get(id).next).isEqualTo(63);
        queue.now=63; worker().tick(); assertThat(queue.rows.get(id).state).isEqualTo("PROCESSED");
    }
    @Test void permanentErrorsRemainDiagnosticAndBusySyncOrExceptionAreRetried() {
        for (String outcome:List.of("NOT_CONFIGURED","INVALID_CONTRACT")) {
            queue.rows.clear(); UUID id=queue.add(); queue.now+=3;
            when(synchronizer.synchronize()).thenReturn(status(outcome)); worker().tick();
            assertThat(queue.rows.get(id).state).isEqualTo("FAILED"); assertThat(queue.rows.get(id).outcome).isEqualTo(outcome);
            queue.now+=10000; worker().tick(); assertThat(queue.rows.get(id).attempts).isEqualTo(1);
        }
        queue.rows.clear(); UUID id=queue.add(); queue.now+=3;
        doThrow(new AdminFailure(409,"synthetic busy")).when(synchronizer).synchronize(); worker().tick();
        assertThat(queue.rows.get(id).outcome).isEqualTo("SYNC_BUSY");
        queue.now+=30; doThrow(new IllegalStateException(SECRET)).when(synchronizer).synchronize(); worker().tick();
        assertThat(queue.rows.get(id).state).isEqualTo("RETRY"); assertThat(queue.rows.get(id).outcome).isEqualTo("WORKER_ERROR");
    }
    @Test void twoWorkersSharingStorageCannotProcessSameBatchSimultaneously() throws Exception {
        queue.add(); queue.now=3;
        CountDownLatch synchronizing=new CountDownLatch(1), release=new CountDownLatch(1);
        when(synchronizer.synchronize()).thenAnswer(i -> {
            synchronizing.countDown(); assertThat(release.await(3,TimeUnit.SECONDS)).isTrue(); return status("SUCCESS");
        });
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> first=executor.submit(() -> worker().tick());
            assertThat(synchronizing.await(3,TimeUnit.SECONDS)).isTrue();
            worker().tick(); verify(synchronizer,times(1)).synchronize();
            release.countDown(); first.get(3,TimeUnit.SECONDS);
            assertThat(queue.rows.values()).allSatisfy(row -> assertThat(row.attempts).isEqualTo(1));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void backoffIsBoundedAndTemporaryFailureNeverDiscardsEventAfterManyAttempts() {
        UUID id=queue.add(); queue.now=3; when(synchronizer.synchronize()).thenReturn(status("ERP_ERROR"));
        for (int i=0;i<20;i++) { worker().tick(); queue.now=queue.rows.get(id).next; }
        assertThat(queue.rows.get(id).state).isEqualTo("RETRY"); assertThat(queue.rows.get(id).attempts).isEqualTo(20);
        assertThat(properties(true,true).retryDelay(20)).isEqualTo(900);
        assertThat(properties(true,true).retryDelay(Integer.MAX_VALUE)).isEqualTo(900);
    }
}
