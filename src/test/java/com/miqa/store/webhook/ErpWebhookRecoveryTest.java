package com.miqa.store.webhook;

import com.miqa.store.admin.AdminFailure;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.webhook.WebhookTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transaction template with simulated JDBC; never opens a database connection. */
class ErpWebhookRecoveryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ErpWebhookQueue queue = new ErpWebhookQueue(mock(DataSource.class), properties(true,true), jdbc, transactions);
    private final UUID event = UUID.fromString(ID), request = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-09T12:00:00Z");
    private final String reason = "Configuracion corregida";
    @BeforeEach void setup() throws Exception {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        when(jdbc.query(contains("SELECT state"), any(RowMapper.class), eq(event))).thenReturn(List.of("FAILED"));
        when(jdbc.query(contains("FROM erp_catalog_webhook_requeues"), any(RowMapper.class), eq(request)))
                .thenReturn(List.of());
        when(jdbc.query(contains("INSERT INTO erp_catalog_webhook_requeues"), any(RowMapper.class),
                eq(request), eq("admin"), eq(reason), eq(3), eq(event))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1); return List.of(mapper.mapRow(auditRow(),0));
                });
        when(jdbc.update(contains("SET state='PENDING'"), any(), eq(event))).thenReturn(1);
    }
    private ResultSet auditRow() throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("request_id",UUID.class)).thenReturn(request);
        when(row.getObject("event_id",UUID.class)).thenReturn(event);
        when(row.getTimestamp("requeued_at")).thenReturn(Timestamp.from(now));
        when(row.getTimestamp("next_attempt_at")).thenReturn(Timestamp.from(now.plusSeconds(3)));
        when(row.getString("admin_id")).thenReturn("admin"); when(row.getString("reason")).thenReturn(reason);
        return row;
    }
    @Test void locksAuditsAndSchedulesOriginalEventBeforeCommitWithoutResettingHistory() {
        var receipt = queue.requeue(event,request,"admin",reason);
        assertThat(receipt.eventId()).isEqualTo(event); assertThat(receipt.status()).isEqualTo("REQUEUED");
        assertThat(receipt.nextAttemptAt()).isEqualTo(now.plusSeconds(3));
        var order = inOrder(jdbc, transactions);
        order.verify(transactions).getTransaction(any());
        order.verify(jdbc).query(contains("FOR UPDATE"), any(RowMapper.class), eq(event));
        order.verify(jdbc).query(contains("FROM erp_catalog_webhook_requeues"), any(RowMapper.class), eq(request));
        order.verify(jdbc).query(contains("previous_attempts,previous_outcome"), any(RowMapper.class),
                eq(request), eq("admin"), eq(reason), eq(3), eq(event));
        order.verify(jdbc).update(argThat(sql -> sql.contains("WHERE event_id=? AND state='FAILED'")
                && !sql.contains("attempts=") && !sql.contains("last_outcome=") && !sql.contains("body_sha256=")),
                eq(Timestamp.from(now.plusSeconds(3))), eq(event));
        order.verify(transactions).commit(any());
    }
    @Test void identicalRequestAfterWorkerFinishedReturnsOriginalReceiptWithoutRequeue() throws Exception {
        when(jdbc.query(contains("SELECT state"), any(RowMapper.class), eq(event))).thenReturn(List.of("PROCESSED"));
        when(jdbc.query(contains("FROM erp_catalog_webhook_requeues"), any(RowMapper.class), eq(request)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1); return List.of(mapper.mapRow(auditRow(),0));
                });
        assertThat(queue.requeue(event,request,"admin",reason).requeuedAt()).isEqualTo(now);
        verify(jdbc,never()).update(anyString(), any(), any());
        verify(jdbc,never()).query(contains("INSERT INTO"), any(RowMapper.class), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> queue.requeue(event,request,"another-admin",reason)).isInstanceOf(AdminFailure.class);
        assertThatThrownBy(() -> queue.requeue(event,request,"admin","Otra correccion realizada")).isInstanceOf(AdminFailure.class);
    }
    @Test void missingOrNonFailedEventsCannotBeRequeued() {
        for (String state : List.of("PENDING","RETRY","PROCESSING","PROCESSED")) {
            when(jdbc.query(contains("SELECT state"), any(RowMapper.class), eq(event))).thenReturn(List.of(state));
            assertThatThrownBy(() -> queue.requeue(event,request,"admin",reason))
                    .isInstanceOfSatisfying(AdminFailure.class, error -> assertThat(error.status()).isEqualTo(409));
        }
        when(jdbc.query(contains("SELECT state"), any(RowMapper.class), eq(event))).thenReturn(List.of());
        assertThatThrownBy(() -> queue.requeue(event,request,"admin",reason))
                .isInstanceOfSatisfying(AdminFailure.class, error -> assertThat(error.status()).isEqualTo(404));
        verify(jdbc,never()).update(anyString(), any(), any());
        verify(transactions,never()).commit(any());
    }
    @Test void updateOrAuditFailureRollsBackAndCannotReturnAcceptedReceipt() {
        when(jdbc.update(contains("SET state='PENDING'"), any(), eq(event))).thenReturn(0);
        assertThatThrownBy(() -> queue.requeue(event,request,"admin",reason)).isInstanceOf(AdminFailure.class);
        verify(transactions).rollback(any()); verify(transactions,never()).commit(any());
        when(jdbc.query(contains("INSERT INTO erp_catalog_webhook_requeues"), any(RowMapper.class),
                eq(request), eq("admin"), eq(reason), eq(3), eq(event)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("synthetic duplicate request ID"));
        assertThatThrownBy(() -> queue.requeue(event,request,"admin",reason))
                .isInstanceOfSatisfying(AdminFailure.class, error -> assertThat(error.status()).isEqualTo(409)).hasNoCause();
        verify(transactions,times(2)).rollback(any());
    }
    @Test void commitFailureIsNeverAcknowledged() {
        doThrow(new org.springframework.transaction.TransactionSystemException("synthetic commit failure"))
                .when(transactions).commit(any());
        assertThatThrownBy(() -> queue.requeue(event,request,"admin",reason))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
    }
    @Test void controlledListingAndInputValidationRejectUnboundedOrInvalidRequests() {
        for (int limit : new int[]{0,101,Integer.MAX_VALUE})
            assertThatThrownBy(() -> queue.failed(limit)).isInstanceOf(AdminFailure.class);
        for (String invalid : List.of("", "       abc", "r".repeat(501), "invalid\nreason", "\uD83D\uDE00".repeat(4)))
            assertThatThrownBy(() -> queue.requeue(event,request,"admin",invalid)).isInstanceOf(AdminFailure.class);
        assertThatThrownBy(() -> queue.requeue(event,null,"admin",reason)).isInstanceOf(AdminFailure.class);
        verifyNoInteractions(transactions);
    }
}
