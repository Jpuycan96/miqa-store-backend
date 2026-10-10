package com.miqa.store.webhook;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.*;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErpWebhookQueueTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ErpWebhookQueue queue = new ErpWebhookQueue(dataSource, properties(true, true), jdbc, transactions);
    private final ErpWebhookReceiver receiver = new ErpWebhookReceiver(queue);
    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus(true));
        when(jdbc.update(contains("INSERT INTO"), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }
    @Test void receiptWaitsForCommitBeforeReturningAndUsesIndependentTransaction() throws Exception {
        CountDownLatch committing = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        doAnswer(i -> { committing.countDown(); assertThat(allowCommit.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(transactions).commit(any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ErpWebhookReceiver.Receipt> receipt = executor.submit(() -> receiver.receive(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(committing.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(receipt.isDone()).isFalse();
            allowCommit.countDown();
            assertThat(receipt.get(3, TimeUnit.SECONDS).status()).isEqualTo("ACCEPTED");
            var order = inOrder(jdbc, transactions);
            order.verify(transactions).getTransaction(argThat(definition ->
                    definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
            order.verify(jdbc).update(contains("INSERT INTO"), any(), any(), any(), any(), any(), any());
            order.verify(transactions).commit(any());
        } finally { allowCommit.countDown(); executor.shutdownNow(); }
    }
    @Test void writeFailureRollsBackAndCommitFailureCannotProduceReceipt() {
        when(jdbc.update(contains("INSERT INTO"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("synthetic failure"));
        assertThatThrownBy(() -> receiver.receive(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(transactions).rollback(any()); verify(transactions, never()).commit(any());
        reset(jdbc, transactions); setup();
        doThrow(new org.springframework.transaction.TransactionSystemException("synthetic commit failure"))
                .when(transactions).commit(any());
        assertThatThrownBy(() -> receiver.receive(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
    }
    @Test void duplicateDoesNotRescheduleAndDifferentBodyWithSameIdIsConflict() {
        var event = receiver.decode(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(jdbc.update(contains("INSERT INTO"), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(event.eventId()))).thenReturn(event.bodyHash());
        assertThat(queue.accept(event)).isFalse();
        verify(jdbc, never()).update(startsWith("UPDATE"), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(event.eventId()))).thenReturn("0".repeat(64));
        assertThatThrownBy(() -> queue.accept(event)).isInstanceOfSatisfying(ErpWebhookFailure.class,
                failure -> assertThat(failure.code()).isEqualTo("EVENT_ID_CONFLICT"));
        verify(transactions).rollback(any());
    }
    private Connection lockConnection(boolean available) throws Exception {
        var connection = mock(Connection.class);
        var acquire = mock(PreparedStatement.class); var release = mock(PreparedStatement.class);
        var acquireResult = mock(ResultSet.class); var releaseResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquireResult); when(release.executeQuery()).thenReturn(releaseResult);
        when(acquireResult.next()).thenReturn(true); when(acquireResult.getBoolean(1)).thenReturn(available);
        when(releaseResult.next()).thenReturn(true); when(releaseResult.getBoolean(1)).thenReturn(true);
        return connection;
    }
    @Test void busyWorkerLockReturnsImmediatelyWithoutWorkOrUnlock() throws Exception {
        var connection = lockConnection(false); var work = mock(Runnable.class);
        assertThat(queue.withWorkerLock(work)).isFalse(); verifyNoInteractions(work);
        verify(connection, never()).prepareStatement("SELECT pg_advisory_unlock(?)"); verify(connection).close();
    }
    @Test void workerExceptionStillUnlocksSameSessionBeforeReturningItToPool() throws Exception {
        var connection = lockConnection(true);
        assertThatThrownBy(() -> queue.withWorkerLock(() -> { throw new IllegalStateException("synthetic failure"); }))
                .isInstanceOf(IllegalStateException.class);
        var order = inOrder(connection);
        order.verify(connection).prepareStatement("SELECT pg_try_advisory_lock(?)");
        order.verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)"); order.verify(connection).close();
        verify(connection, never()).abort(any());
    }
    @Test void uncertainLockAcquisitionOrFailedReleaseAbortSessionAndHideInfrastructureDetails() throws Exception {
        var connection = lockConnection(true);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)"))
                .thenThrow(new SQLException(SECRET + " sensitive infrastructure"));
        assertThatThrownBy(() -> queue.withWorkerLock(() -> fail("must not run")))
                .hasMessage("Webhook worker connection unavailable").hasNoCause();
        verify(connection).abort(any());
        connection = lockConnection(true);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)"))
                .thenThrow(new SQLException(SECRET + " sensitive infrastructure"));
        assertThatThrownBy(() -> queue.withWorkerLock(() -> {}))
                .hasMessage("Webhook worker connection unavailable").hasNoCause();
        verify(connection).abort(any());
    }
}
