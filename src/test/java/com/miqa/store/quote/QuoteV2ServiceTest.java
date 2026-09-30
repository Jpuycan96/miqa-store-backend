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
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final QuoteRequestCanonicalizer legacy = new QuoteRequestCanonicalizer(validation.getValidator(), mapper);
    private final QuoteV2Service service = new QuoteV2Service(repository, legacy, catalog, erp, validation.getValidator(), mapper, transactions);
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
        verify(transactions,times(2)).commit(any());
        clearInvocations(erp);
        assertThat(service.submit(KEY,input()).replay()).isTrue();
        verifyNoInteractions(erp);
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
    @Test void v2HasSameBodyLimitAndNoStoreProtection() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST",QuoteRequestBodyFilter.PATH+"/v2");
        request.setContent(new byte[65537]);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new QuoteRequestBodyFilter(mapper,new QuoteRequestRateLimit(10)).doFilter(request,response,(req,res) -> fail("Oversized v2 reached MVC"));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
