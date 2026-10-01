package com.miqa.store.quote;

import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.miqa.store.quote.ErpQuoteSelectionTest.*;

class QuoteV2ServiceTest {
    private static final String KEY = "8d1f6e46-ab47-4c6c-bf46-7af87fa519a4";
    private final QuoteRequestRepository repository = mock(QuoteRequestRepository.class);
    private final QuoteCatalog catalog = mock(QuoteCatalog.class);
    private final ErpQuoteSelection erp = mock(ErpQuoteSelection.class);
    private final com.miqa.store.pricing.ErpPricing pricing = mock(com.miqa.store.pricing.ErpPricing.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final QuoteRequestCanonicalizer legacy = new QuoteRequestCanonicalizer(validation.getValidator(), mapper);
    private final QuoteV2Service service = new QuoteV2Service(repository, legacy, catalog, erp, pricing, validation.getValidator(), mapper, transactions);
    private QuoteRequestCanonicalizer.Canonical saved;
    private List<QuoteV2Dtos.StoredItem> savedItems;
    private QuoteV2Dtos.Submission input() {
        return new QuoteV2Dtos.Submission(2, new QuoteRequestDtos.Contact("Cliente", "999999999", null), null,
                List.of(item("1","10",null,"1.5",Map.of())));
    }
    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus(true));
        when(repository.find(any())).thenAnswer(call -> saved == null ? Optional.empty()
                : Optional.of(new QuoteRequestRepository.Receipt("MIQA-000123",Instant.EPOCH,saved.hash())));
        when(erp.snapshot(any())).thenAnswer(call -> ErpQuoteSelection.snapshot(resolved("ESCALA","SIN_MODELO",true),call.getArgument(0)));
        when(pricing.evaluate(any())).thenReturn(new com.miqa.store.pricing.PricingDtos.Historical(
                com.miqa.store.pricing.PricingDtos.Status.PRICE_AVAILABLE,"27.50","PEN",true,"TOTAL_LINEA","ESCALA",
                new com.miqa.store.pricing.PricingDtos.BillableBase("1.5","unidad"),"revision-price",Instant.EPOCH));
        when(repository.insertV2(any(),anyList())).thenAnswer(call -> {
            saved=call.getArgument(0); savedItems=call.getArgument(1);
            return new QuoteRequestRepository.Receipt("MIQA-000123",Instant.EPOCH,saved.hash());
        });
    }
    @AfterEach void cleanup() { validation.close(); }
    @Test void erpRequestCommitsSnapshotAndReplayDoesNotRevalidateCatalog() {
        assertThat(service.submit(KEY,input()).replay()).isFalse();
        assertThat(savedItems).hasSize(1);
        assertThat(savedItems.getFirst().snapshot()).isInstanceOf(QuoteV2Dtos.ErpSnapshot.class);
        var snapshot = (QuoteV2Dtos.ErpSnapshot)savedItems.getFirst().snapshot();
        assertThat(snapshot.pricing().amount()).isEqualTo("27.50");
        assertThat(snapshot.pricing().pricingRevision()).isEqualTo("revision-price");
        assertThat(mapper.writeValueAsString(snapshot)).doesNotContain("X-ERP-Service-Key","apiKey");
        verify(transactions,times(3)).commit(any());
        clearInvocations(erp,pricing);
        assertThat(service.submit(KEY,input()).replay()).isTrue();
        verifyNoInteractions(erp,pricing);
        verify(repository,times(1)).insertV2(any(),anyList());
    }
    @Test void equivalentDecimalScaleHasSameHashButAnotherSelectionConflicts() {
        service.submit(KEY,input());
        var equivalent = new QuoteV2Dtos.Submission(2,input().contact(),null,List.of(item("1","10",null,"1.500",Map.of())));
        assertThat(service.submit(KEY,equivalent).replay()).isTrue();
        var changed = new QuoteV2Dtos.Submission(2,input().contact(),null,List.of(item("1","11",null,"1.5",Map.of())));
        assertThatThrownBy(() -> service.submit(KEY,changed)).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(409));
    }
    @Test void mixedRequestKeepsOrderAndLegacySnapshotVersion() {
        var legacyInput = new QuoteV2Dtos.Item("legacy",com.miqa.store.catalog.ProductSaleType.QUANTITY,null,BigDecimal.valueOf(5),null,null,null,List.of(),null,null);
        var legacySnapshot = new QuoteSnapshot(1,"legacy","Legacy","legacy",null,com.miqa.store.catalog.ProductSaleType.QUANTITY,
                5,"unidad",null,null,null,null,null,null,List.of(),null,null);
        when(catalog.snapshot(any())).thenReturn(legacySnapshot);
        var mixed = new QuoteV2Dtos.Submission(2,input().contact(),null,List.of(legacyInput,input().items().getFirst()));
        service.submit(KEY,mixed);
        assertThat(savedItems).extracting(QuoteV2Dtos.StoredItem::productId).containsExactly("legacy","banner");
        assertThat(savedItems.getFirst().snapshot()).isEqualTo(legacySnapshot);
    }
    @Test void rejectsConflictingLegacyFieldsAndInvalidDecimalLegacyQuantity() {
        var conflict = new QuoteV2Dtos.Item("banner",com.miqa.store.catalog.ProductSaleType.AREA,null,BigDecimal.ONE,null,null,null,List.of(),null,input().items().getFirst().erp());
        var decimalLegacy = new QuoteV2Dtos.Item("legacy",com.miqa.store.catalog.ProductSaleType.QUANTITY,null,new BigDecimal("1.5"),null,null,null,List.of(),null,null);
        for(var value: List.of(conflict,decimalLegacy)) assertThatThrownBy(() -> service.submit(KEY,new QuoteV2Dtos.Submission(2,input().contact(),null,List.of(value))))
                .isInstanceOf(QuoteRequestFailure.class);
        verifyNoInteractions(repository,erp,catalog);
    }
    @Test void snapshotFailureRollsBackWithoutPersistingRequest() {
        doThrow(QuoteRequestFailure.catalogChanged()).when(erp).snapshot(any());
        assertThatThrownBy(() -> service.submit(KEY,input())).isInstanceOf(QuoteRequestFailure.class);
        verify(transactions).rollback(any());
        verify(repository,never()).insertV2(any(),anyList());
    }
    @Test void quoteRequiredIsHistoricalAndFailuresNeverInsert() {
        when(pricing.evaluate(any())).thenReturn(com.miqa.store.pricing.PricingDtos.Historical.state(
                com.miqa.store.pricing.PricingDtos.Status.QUOTE_REQUIRED));
        service.submit(KEY,input());
        assertThat(((QuoteV2Dtos.ErpSnapshot)savedItems.getFirst().snapshot()).pricing().status())
                .isEqualTo(com.miqa.store.pricing.PricingDtos.Status.QUOTE_REQUIRED);
        saved=null;
        clearInvocations(repository);
        for (var status : List.of(com.miqa.store.pricing.PricingDtos.Status.CONFIGURATION_STALE,
                com.miqa.store.pricing.PricingDtos.Status.CONFIGURATION_INVALID,
                com.miqa.store.pricing.PricingDtos.Status.TEMPORARILY_UNAVAILABLE)) {
            when(pricing.evaluate(any())).thenReturn(com.miqa.store.pricing.PricingDtos.Historical.state(status));
            assertThatThrownBy(() -> service.submit(KEY,input())).isInstanceOfSatisfying(com.miqa.store.pricing.PricingFailure.class,
                    ex -> assertThat(ex.status()).isEqualTo(status));
        }
        verify(repository,never()).insertV2(any(),anyList());
    }
    @Test void httpEvaluationOccursBetweenReadCommitAndWriteStart() {
        service.submit(KEY,input());
        var order = inOrder(transactions,erp,pricing,repository);
        order.verify(repository).find(any());
        order.verify(transactions).commit(any());
        order.verify(erp).snapshot(any());
        order.verify(transactions).commit(any());
        order.verify(pricing).evaluate(any());
        order.verify(transactions).getTransaction(any());
        order.verify(repository).insertV2(any(),anyList());
        order.verify(transactions).commit(any());
    }
    @Test void publicationChangedDuringHttpRejectsTheWholeRequest() {
        when(pricing.evaluate(any())).thenAnswer(call -> {
            doThrow(QuoteRequestFailure.catalogChanged()).when(erp).snapshot(any());
            return com.miqa.store.pricing.PricingDtos.Historical.state(com.miqa.store.pricing.PricingDtos.Status.QUOTE_REQUIRED);
        });
        assertThatThrownBy(() -> service.submit(KEY,input())).isInstanceOf(QuoteRequestFailure.class);
        verify(repository,never()).insertV2(any(),anyList());
        verify(transactions).rollback(any());
    }
    @Test void legacyOnlyNeverCallsPricing() {
        var item = new QuoteV2Dtos.Item("legacy",com.miqa.store.catalog.ProductSaleType.QUANTITY,null,BigDecimal.ONE,null,null,null,List.of(),null,null);
        service.submit(KEY,new QuoteV2Dtos.Submission(2,input().contact(),null,List.of(item)));
        verifyNoInteractions(erp,pricing);
    }
    @Test void idempotencyCollisionRollsBackAndRecoversWinner() {
        doAnswer(call -> {
            saved=call.getArgument(0);
            var details = new org.postgresql.util.ServerErrorMessage("SERROR\0C23505\0Mduplicate\0n"
                    + QuoteRequestRepository.IDEMPOTENCY_CONSTRAINT + "\0\0");
            throw new org.springframework.dao.DataIntegrityViolationException("conflict",new org.postgresql.util.PSQLException(details));
        }).when(repository).insertV2(any(),anyList());
        var result = service.submit(KEY,input());
        assertThat(result.replay()).isTrue();
        assertThat(result.confirmation().reference()).isEqualTo("MIQA-000123");
        verify(transactions).rollback(any());
        verify(repository,times(3)).find(any());
    }
    @Test void persistenceFailureRollsBackAllItemsWithoutRetryingErp() {
        doThrow(new IllegalStateException("synthetic write failure")).when(repository).insertV2(any(),anyList());
        assertThatThrownBy(() -> service.submit(KEY,input())).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(pricing).evaluate(any());
    }
    @Test void mixedRequestFailureDoesNotPersistLegacyEither() {
        var legacyInput = new QuoteV2Dtos.Item("legacy",com.miqa.store.catalog.ProductSaleType.QUANTITY,null,BigDecimal.ONE,null,null,null,List.of(),null,null);
        when(pricing.evaluate(any())).thenReturn(com.miqa.store.pricing.PricingDtos.Historical.state(
                com.miqa.store.pricing.PricingDtos.Status.TEMPORARILY_UNAVAILABLE));
        assertThatThrownBy(() -> service.submit(KEY,new QuoteV2Dtos.Submission(2,input().contact(),null,
                List.of(legacyInput,input().items().getFirst())))).isInstanceOf(com.miqa.store.pricing.PricingFailure.class);
        verify(repository,never()).insertV2(any(),anyList());
    }
    @Test void v2HasSameBodyLimitAndNoStoreProtection() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST",QuoteRequestBodyFilter.PATH+"/v2");
        request.setContent(new byte[65537]);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new QuoteRequestBodyFilter(mapper,new QuoteRequestRateLimit(10)).doFilter(request,response,(req,res) -> fail("Oversized v2 reached MVC"));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
