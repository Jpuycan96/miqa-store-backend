package com.miqa.store.erp;

import com.miqa.store.admin.AdminFailure;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.miqa.store.erp.ErpCatalogDtos.*;

class ErpCatalogTest {
    static final String JSON = """
            {"contractVersion":1,"sourceSystem":"ERP_GIGANTOGRAFIAS","erpServiceId":"17",
             "nombreReferencia":"SELLOS","categoria":{"erpCategoryId":"3","nombreReferencia":"ERP category"},
             "elegible":true,"disponible":true,"estadoConfiguracion":"CONFIGURADA","motivos":[],
             "configurationVersion":"2","catalogRevision":"%s","evaluatedAt":"2026-09-29T10:00:00Z",
             "configuracion":{"cantidad":{"unidad":"unidad","minimo":"1","incrementoSugerido":"1",
                 "multiploObligatorio":null,"permiteDecimales":false,"precision":0,"maximo":"99999999"},
                 "modoMaterial":"FIJO","formaCotizacion":"ESCALA",
                 "medidas":{"modo":"NINGUNA","unidad":null,"camposRequeridos":["cantidad"]},
                 "materiales":[{"erpMaterialId":"44","nombreReferencia":"Aprobado","modoModelos":"FIJO",
                     "modelos":[{"erpModelId":"81","nombreReferencia":"Aprobado"}]}]}}
            """.formatted("a".repeat(64));
    private final ErpCatalogClient client = mock(ErpCatalogClient.class);
    private final ErpCatalogRepository repository = mock(ErpCatalogRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ErpCatalogContract item = new ErpCatalogClient("", "").decode("[" + JSON + "]").getFirst();
    private ErpCatalogService service;

    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus(true));
        when(repository.trySyncLock()).thenReturn(true);
        when(repository.revisions()).thenReturn(Map.of());
        when(repository.status()).thenReturn(new SyncStatus("SUCCESS", Instant.now(), Instant.now(), 1, 1, 0));
        when(client.fetchAvailable()).thenReturn(List.of(item));
        service = new ErpCatalogService(client, repository, transactions);
    }
    @Test void deserializesActualV1ShapeWithTextIdsDecimalsAndNestedModels() {
        ErpCatalogValidation.listing(List.of(item));
        assertThat(item.erpServiceId()).isEqualTo("17");
        assertThat(item.configuracion().cantidad().minimo()).isEqualTo("1");
        assertThat(item.configuracion().materiales().getFirst().modelos().getFirst().erpModelId()).isEqualTo("81");
        assertThat(item.categoria().erpCategoryId()).isEqualTo("3");
        var area = new ErpCatalogClient("", "").decode("[" + JSON.replace("ESCALA", "M2")
                .replace("NINGUNA", "SUPERFICIE").replace("\"unidad\":null", "\"unidad\":\"m\"") + "]");
        ErpCatalogValidation.listing(area);
        assertThat(area.getFirst().configuracion().formaCotizacion()).isEqualTo("M2");
    }
    @Test void initialSyncPersistsProjectionAndCommitsResult() {
        service.synchronize();
        var order = inOrder(repository, transactions);
        order.verify(repository).trySyncLock();
        order.verify(repository).revisions();
        order.verify(repository).upsert(eq(item), any());
        order.verify(repository).success(any(), eq(1), eq(1), eq(0));
        order.verify(repository).status();
        order.verify(transactions).commit(any());
    }
    @Test void unchangedRevisionOnlyRefreshesFreshnessAndRestoresAvailability() {
        when(repository.revisions()).thenReturn(Map.of("17", item.catalogRevision()));
        service.synchronize();
        verify(repository).seen(eq("17"), any());
        verify(repository, never()).upsert(any(), any());
        verify(repository).success(any(), eq(1), eq(0), eq(0));
    }
    @Test void changedRevisionReplacesProjection() {
        when(repository.revisions()).thenReturn(Map.of("17", "b".repeat(64)));
        service.synchronize();
        verify(repository).upsert(eq(item), any());
        verify(repository, never()).seen(any(), any());
    }
    @Test void absentServiceBecomesPendingWithoutDeletingItsReferences() {
        when(repository.revisions()).thenReturn(Map.of("17", item.catalogRevision(), "18", "b".repeat(64)));
        when(repository.missing(eq("18"), any())).thenReturn(1);
        service.synchronize();
        verify(repository).missing(eq("18"), any());
        verify(repository).success(any(), eq(1), eq(0), eq(1));
    }
    @Test void validEmptyArrayReconcilesAllKnownServices() {
        when(client.fetchAvailable()).thenReturn(List.of());
        when(repository.revisions()).thenReturn(Map.of("17", item.catalogRevision()));
        service.synchronize();
        verify(repository).missing(eq("17"), any());
    }
    @Test void networkFailureDoesNotTouchProjectionOrLastSuccess() {
        when(client.fetchAvailable()).thenThrow(new ErpCatalogFailure("ERP_ERROR"));
        service.synchronize();
        verify(repository).failure(any(), eq("ERP_ERROR"));
        verify(repository, never()).revisions();
        verify(repository, never()).missing(any(), any());
        verify(repository, never()).success(any(), anyInt(), anyInt(), anyInt());
    }
    @Test void duplicateOrInvalidLateEntryRejectsWholeResponseBeforeAnyMutation() {
        for (var entries : List.of(List.of(item, item), List.of(item,
                new ErpCatalogClient("", "").decode("[" + JSON.replace("\"erpServiceId\":\"17\"", "\"erpServiceId\":\"18\"")
                        .replace("\"disponible\":true", "\"disponible\":false") + "]").getFirst()))) {
            when(client.fetchAvailable()).thenReturn(entries);
            service.synchronize();
        }
        verify(repository, times(2)).failure(any(), eq("INVALID_CONTRACT"));
        verify(repository, never()).revisions();
        verify(repository, never()).upsert(any(), any());
    }
    @Test void rejectsNullEnvelopeTruncatedJsonAndUnexpectedFields() {
        var decoder = new ErpCatalogClient("", "");
        for (String body : List.of("null", "{}", "[" + JSON, "[] []", "[null]",
                "[" + JSON.replace("\"elegible\":true", "\"secret\":\"must-not-persist\",\"elegible\":true") + "]")) {
            assertThatThrownBy(() -> decoder.decode(body)).isInstanceOf(ErpCatalogFailure.class)
                    .hasMessage("INVALID_CONTRACT").hasNoCause();
        }
    }
    @Test void overlappingSynchronizationIsRejectedBeforeContactingErp() {
        when(repository.trySyncLock()).thenReturn(false);
        assertThatThrownBy(service::synchronize).isInstanceOfSatisfying(AdminFailure.class,
                ex -> assertThat(ex.status()).isEqualTo(409));
        verifyNoInteractions(client);
        verify(transactions).rollback(any());
    }
    @Test void databaseFailureRollsBackWholeReconciliation() {
        doThrow(new IllegalStateException("simulated")).when(repository).upsert(any(), any());
        assertThatThrownBy(service::synchronize).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(repository, never()).success(any(), anyInt(), anyInt(), anyInt());
    }
    @Test void differentProductsMayBindSameServiceAndRebindingLocksExistingProduct() {
        when(repository.serviceExists("17")).thenReturn(true);
        for (String product : List.of("editorial-a", "editorial-b")) {
            when(repository.lockProduct(product)).thenReturn(true);
            when(repository.binding(product)).thenReturn(Optional.of(new ProductErpBinding(product, "17", true,
                    "AVAILABLE", Instant.now(), Instant.now(), Instant.now())));
            assertThat(service.bind(product, new BindingInput("17", true)).erpServiceId()).isEqualTo("17");
            var order = inOrder(repository);
            order.verify(repository).lockProduct(product);
            order.verify(repository).bind(product, new BindingInput("17", true));
        }
    }
    @Test void unknownProductOrServiceCannotCreateBinding() {
        assertThatThrownBy(() -> service.bind("missing", new BindingInput("17", true))).isInstanceOf(AdminFailure.class);
        when(repository.lockProduct("exists")).thenReturn(true);
        assertThatThrownBy(() -> service.bind("exists", new BindingInput("17", true))).isInstanceOf(AdminFailure.class);
        verify(repository, never()).bind(any(), any());
    }
    @Test void adminResultContainsNoTechnicalKeyOrRemoteErrorDetails() {
        String key = "synthetic-test-key";
        assertThat(new ErpCatalogClient("", key).toString()).doesNotContain(key);
        when(repository.status()).thenReturn(new SyncStatus("ERP_ERROR", Instant.now(), null, 0, 0, 0));
        when(client.fetchAvailable()).thenThrow(new ErpCatalogFailure("ERP_ERROR"));
        var response = new ErpCatalogController(service).synchronize();
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(JsonMapper.builder().build().writeValueAsString(response.getBody()))
                .doesNotContain(key, "apiKey", "X-ERP-Service-Key", "baseUrl");
    }
}
