package com.miqa.store.quote;

import org.junit.jupiter.api.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.quote.QuoteRequestCanonicalizerTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteRequestServiceTest {
    private final QuoteRequestRepository repository = mock(QuoteRequestRepository.class);
    private final QuoteCatalog catalog = mock(QuoteCatalog.class);
    private final QuoteRequestCanonicalizer canonicalizer = mock(QuoteRequestCanonicalizer.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final QuoteRequestDtos.Submission input = submission(quantity(50));
    private final UUID key = UUID.fromString(KEY);
    private final String hash = "a".repeat(64);
    private final QuoteRequestRepository.Receipt receipt = new QuoteRequestRepository.Receipt("MIQA-000001", Instant.parse("2026-09-28T10:00:00Z"), hash);
    private QuoteRequestService service;

    @BeforeEach void setup() {
        when(canonicalizer.canonicalize(KEY, input)).thenReturn(new QuoteRequestCanonicalizer.Canonical(key, hash, input));
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus(true));
        when(repository.find(key)).thenReturn(Optional.empty());
        service = new QuoteRequestService(repository, catalog, canonicalizer, transactions);
    }

    @Test void newSubmissionCommitsBeforeReturningConfirmation() {
        when(repository.insert(any(), anyList())).thenReturn(receipt);
        var result = service.submit(KEY, input);
        assertThat(result.replay()).isFalse();
        assertThat(result.confirmation()).isEqualTo(receipt.confirmation());
        var order = inOrder(repository, catalog, transactions);
        order.verify(repository).find(key);
        order.verify(transactions).commit(any());
        order.verify(repository).find(key);
        order.verify(catalog).snapshot(input.items().getFirst());
        order.verify(repository).insert(any(), anyList());
        order.verify(transactions).commit(any());
        verify(transactions).getTransaction(argThat(def -> def.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ
                && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }
    @Test void replayDoesNotReadOrRevalidateCatalog() {
        when(repository.find(key)).thenReturn(Optional.of(receipt));
        assertThat(service.submit(KEY, input).replay()).isTrue();
        verifyNoInteractions(catalog);
        verify(repository, never()).insert(any(), anyList());
    }
    @Test void differentHashConflictsBeforeCatalogRead() {
        when(repository.find(key)).thenReturn(Optional.of(new QuoteRequestRepository.Receipt(receipt.reference(), receipt.receivedAt(), "b".repeat(64))));
        assertThatThrownBy(() -> service.submit(KEY, input)).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(409));
        verifyNoInteractions(catalog);
    }
    @Test void racingIdempotencyInsertRollsBackThenReadsWinnerInNewTransaction() {
        when(repository.find(key)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(receipt));
        when(repository.insert(any(), anyList())).thenThrow(duplicate(QuoteRequestRepository.IDEMPOTENCY_CONSTRAINT));
        assertThat(service.submit(KEY, input).replay()).isTrue();
        var order = inOrder(repository, transactions);
        order.verify(repository).insert(any(), anyList());
        order.verify(transactions).rollback(any());
        order.verify(transactions).getTransaction(argThat(def -> def.isReadOnly()
                && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(repository).find(key);
        order.verify(transactions).commit(any());
    }
    @Test void racingDifferentPayloadConflictsAfterRollback() {
        when(repository.find(key)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(new QuoteRequestRepository.Receipt(receipt.reference(), receipt.receivedAt(), "b".repeat(64))));
        when(repository.insert(any(), anyList())).thenThrow(duplicate(QuoteRequestRepository.IDEMPOTENCY_CONSTRAINT));
        assertThatThrownBy(() -> service.submit(KEY, input)).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(409));
        verify(transactions).rollback(any());
    }
    @Test void unrelatedIntegrityFailuresNeverBecomeReplay() {
        var failure = duplicate("quote_requests_reference_key");
        when(repository.insert(any(), anyList())).thenThrow(failure);
        assertThatThrownBy(() -> service.submit(KEY, input)).isSameAs(failure);
        verify(repository, times(2)).find(key);
        verify(transactions).rollback(any());
        assertThat(QuoteRequestService.isIdempotencyCollision(new DataIntegrityViolationException("idempotency"))).isFalse();
    }
    @Test void referenceExpandsInsteadOfTruncatingAtOneMillion() {
        assertThat(QuoteRequestRepository.reference(1)).isEqualTo("MIQA-000001");
        assertThat(QuoteRequestRepository.reference(999999)).isEqualTo("MIQA-999999");
        assertThat(QuoteRequestRepository.reference(1000000)).isEqualTo("MIQA-1000000");
        assertThat(QuoteRequestRepository.reference(Long.MAX_VALUE)).isEqualTo("MIQA-9223372036854775807");
    }
    private DataIntegrityViolationException duplicate(String constraint) {
        var details = new ServerErrorMessage("SERROR\0C23505\0Mduplicate\0n" + constraint + "\0\0");
        return new DataIntegrityViolationException("database conflict", new PSQLException(details));
    }
}
