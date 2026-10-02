package com.miqa.store.export;

import com.miqa.store.quote.*;
import com.miqa.store.pricing.PricingDtos;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.export.RequestExportDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestExportServiceTest {
    static final Instant START=Instant.parse("2026-10-01T00:00:00Z"), END=START.plusSeconds(86400);
    private final RequestExportRepository repo=mock(RequestExportRepository.class);
    private final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    private final RequestExportService service=new RequestExportService(repo,transactions);
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final List<Summary> visible=new ArrayList<>();
    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus(true));
        when(repo.now()).thenReturn(END);
        when(repo.list(any())).thenAnswer(c -> {
            RequestExportCursor q=c.getArgument(0);
            return visible.stream().filter(s -> !s.createdAt().isBefore(q.from()) && s.createdAt().isBefore(q.before()))
                    .filter(s -> q.afterTime()==null || s.createdAt().isAfter(q.afterTime())
                            || s.createdAt().equals(q.afterTime()) && s.id().compareTo(q.afterId())>0)
                    .sorted(Comparator.comparing(Summary::createdAt).thenComparing(Summary::id)).limit(q.limit()+1).toList();
        });
    }
    static Summary summary(String id,Instant at) { return new Summary(id,"MIQA-000010","TIENDA_VIRTUAL","RECIBIDA",at,at); }
    @Test void pagesDeterministicallyAcrossEqualTimestampsAndRepeatsWithoutDuplicates() {
        visible.addAll(List.of(summary("c",START),summary("b",START),summary("a",START),summary("d",START.plusSeconds(1))));
        var first=service.list(null,START.toString(),null,2);
        assertThat(first.requests()).extracting(Summary::id).containsExactly("a","b");
        assertThat(first.hasMore()).isTrue();
        var second=service.list(first.nextCursor(),null,null,null);
        assertThat(second.requests()).extracting(Summary::id).containsExactly("c","d");
        assertThat(second.nextCursor()).isNull(); assertThat(second.hasMore()).isFalse();
        assertThat(service.list(first.nextCursor(),null,null,null)).isEqualTo(second);
        assertThat(first.window()).isEqualTo(second.window());
    }
    @Test void lateCommitBehindCursorIsRecoveredByReplayingWindowNotByAdvancingReference() {
        visible.addAll(List.of(summary("b",START),summary("c",START.plusSeconds(1))));
        var page=service.list(null,START.toString(),END.toString(),1);
        visible.add(summary("a",START.minusSeconds(1)));
        // The consumer must reconcile older windows too; no bounded overlap guarantees arbitrarily late commits.
        assertThat(service.list(page.nextCursor(),null,null,null).requests()).extracting(Summary::id).containsExactly("c");
        assertThat(service.list(null,null,END.toString(),100).requests()).extracting(Summary::id).containsExactly("a","b","c");
    }
    @Test void replayingSameWindowRecoversLateRowBehindItsCursor() {
        visible.addAll(List.of(summary("b",START),summary("c",START.plusSeconds(1))));
        var first=service.list(null,START.toString(),END.toString(),1);
        visible.add(summary("a",START));
        assertThat(service.list(first.nextCursor(),null,null,null).requests()).extracting(Summary::id).containsExactly("c");
        assertThat(service.list(null,first.window().createdFrom().toString(),first.window().createdBefore().toString(),100).requests())
                .extracting(Summary::id).containsExactly("a","b","c");
    }
    @Test void inclusiveLowerExclusiveUpperAndEmptyPages() {
        visible.addAll(List.of(summary("a",START.minusNanos(1000)),summary("b",START),summary("c",END)));
        assertThat(service.list(null,START.toString(),END.toString(),100).requests()).extracting(Summary::id).containsExactly("b");
        visible.clear(); var page=service.list(null,null,null,null);
        assertThat(page.requests()).isEmpty(); assertThat(page.nextCursor()).isNull(); assertThat(page.hasMore()).isFalse();
    }
    @Test void invalidQueriesAndMixedCursorParametersAreRejected() {
        for(String cursor:List.of("", "not-a-cursor", "x".repeat(513)))
            assertThatThrownBy(() -> service.list(cursor,null,null,null)).isInstanceOf(RequestExportFailure.class);
        assertThatThrownBy(() -> service.list(null,null,null,101)).isInstanceOf(RequestExportFailure.class);
        assertThatThrownBy(() -> service.list(null,END.toString(),START.toString(),1)).isInstanceOf(RequestExportFailure.class);
        String cursor=new RequestExportCursor(START,END,START,"a",2).encode();
        assertThatThrownBy(() -> service.list(cursor,START.toString(),null,null)).isInstanceOf(RequestExportFailure.class);
        assertThat(RequestExportCursor.decode(cursor).encode()).isEqualTo(cursor);
    }
    static QuoteSnapshot legacy() {
        return new QuoteSnapshot(1,"legacy-product","Historical legacy","old-slug",new QuoteSnapshot.Category("cat","Historic category","cat"),
                com.miqa.store.catalog.ProductSaleType.AREA,2,"unidad",null,null,BigDecimal.ONE,new BigDecimal("2"),new BigDecimal("2"),
                new QuoteSnapshot.Option("miqa-material","Legacy material"),List.of(),null,"Synthetic item note");
    }
    static QuoteV2Dtos.ErpSnapshot erp(boolean pricing) {
        var snapshot=ErpQuoteSelection.snapshot(ErpQuoteSelectionTest.resolved("M2","SIN_MODELO",false),
                ErpQuoteSelectionTest.item("1","10",null,"1",Map.of("ancho",BigDecimal.ONE,"alto",new BigDecimal("2"))));
        return !pricing ? snapshot : snapshot.withPricing(new PricingDtos.Historical(PricingDtos.Status.PRICE_AVAILABLE,
                "73.25","PEN",true,"TOTAL_LINEA","M2",new PricingDtos.BillableBase("2","M2"),"historical-revision",START));
    }
    @Test void detailExportsBothTablesLegacyErpAndFullHistoricalPricingWithoutPrivateFields() {
        when(repo.header("request")).thenReturn(Optional.of(new RequestExportRepository.Header(summary("request",START),
                new Contact("Synthetic contact","999999999",null),"Synthetic request note")));
        String historical=mapper.writeValueAsString(legacy());
        historical=historical.substring(0,historical.length()-1)+",\"request_hash\":\"hidden-hash\",\"apiKey\":\"hidden-key\"}";
        when(repo.items("request")).thenReturn(List.of(new RequestExportRepository.StoredItem("v1:item-a",1,"legacy-product",historical),
                new RequestExportRepository.StoredItem("v2:item-b",2,"banner",mapper.writeValueAsString(erp(true)))));
        var result=service.detail("request");
        assertThat(result.contact().name()).isEqualTo("Synthetic contact");
        assertThat(result.items()).extracting(Item::id).containsExactly("v1:item-a","v2:item-b");
        assertThat(result.items().getFirst().type()).isEqualTo("LEGACY");
        assertThat(result.items().getFirst().erp()).isNull();
        var snapshot=result.items().getLast().erp();
        assertThat(snapshot.erpServiceId()).isEqualTo("1");
        assertThat(snapshot.materialName()).isEqualTo(erp(true).materialName());
        assertThat(snapshot.pricing()).isEqualTo(erp(true).pricing());
        String json=mapper.writeValueAsString(result);
        assertThat(json).doesNotContain("idempotency","request_hash","hidden-hash","hidden-key","apiKey");
        assertThat(result.toString()).doesNotContain("Synthetic");
        assertThat(result.items().getLast().toString()).doesNotContain("Synthetic");
        verify(transactions).getTransaction(argThat(def -> def.isReadOnly()
                && def.getIsolationLevel()==org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(transactions).commit(any());
    }
    @Test void historicalErpWithoutPricingAndLegacyInV2RemainReadable() {
        String json=mapper.writeValueAsString(erp(false)).replace(",\"pricing\":null","");
        var item=service.item(new RequestExportRepository.StoredItem("v2:b",1,"banner",json));
        assertThat(item.erp().pricing()).isNull();
        assertThat(service.item(new RequestExportRepository.StoredItem("v2:a",2,"legacy-product",mapper.writeValueAsString(legacy()))).type()).isEqualTo("LEGACY");
    }
    @Test void missingAndUnknownOrCorruptSnapshotsFailExplicitlyWithoutPayload() {
        when(repo.header("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("missing")).isInstanceOfSatisfying(RequestExportFailure.class,e -> assertThat(e.status).isEqualTo(404));
        verify(repo,never()).items(any());
        for(String json:List.of("{", "{\"schemaVersion\":3,\"contact\":\"private\"}"))
            assertThatThrownBy(() -> service.item(new RequestExportRepository.StoredItem("v2:x",1,"p",json)))
                    .isInstanceOfSatisfying(RequestExportFailure.class,e -> assertThat(e.status).isEqualTo(409)).hasNoCause()
                    .hasMessageNotContaining("private");
    }
}
