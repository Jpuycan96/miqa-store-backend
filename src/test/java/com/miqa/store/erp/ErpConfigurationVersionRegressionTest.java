package com.miqa.store.erp;

import com.miqa.store.pricing.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real sync/payload/public snapshot/pricing code, with JDBC/ERP transport simulated and no DB. */
class ErpConfigurationVersionRegressionTest {
    private static ErpCatalogContract contract(String version) {
        var source = new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON + "]").getFirst();
        var config = new ErpCatalogContract.Configuration(
                new ErpCatalogContract.Quantity("unidad", "50", "50", null, false, 0, "1000"),
                "FIJO", "ESCALA", source.configuracion().medidas(),
                List.of(new ErpCatalogContract.Material("44", "Approved material", "SIN_MODELO", List.of())));
        return new ErpCatalogContract(source.contractVersion(), source.sourceSystem(), source.erpServiceId(),
                source.nombreReferencia(), source.categoria(), source.elegible(), source.disponible(),
                source.estadoConfiguracion(), source.motivos(), version, source.catalogRevision(), Instant.now(), config);
    }
    @Test void resyncRepairsVersionZeroWithSameHashAndPricingSendsVersionOne() throws Exception {
        var mapper = JsonMapper.builder().build();
        var storedPayload = new AtomicReference<>(mapper.writeValueAsString(contract("0")));
        var writes = new AtomicInteger();
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class))).thenReturn(true);
        doAnswer(call -> {
            var stored = mapper.readValue(storedPayload.get(), ErpCatalogContract.class);
            var row = mock(ResultSet.class);
            when(row.getString(1)).thenReturn(stored.erpServiceId());
            when(row.getString(2)).thenReturn(call.<String>getArgument(0).contains("payload ->>")
                    ? stored.configurationVersion() : stored.catalogRevision());
            call.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        when(jdbc.update(startsWith("INSERT INTO erp_catalog_services"), any(Object[].class))).thenAnswer(call -> {
            var args = (Object[]) call.getRawArguments()[1];
            storedPayload.set((String) args[2]); writes.incrementAndGet(); return 1;
        });
        when(jdbc.query(anyString(), any(RowMapper.class), eq("public-product"))).thenAnswer(call -> {
            var row = mock(ResultSet.class);
            when(row.getString("id")).thenReturn("public-product");
            when(row.getString("name")).thenReturn("Editorial product");
            when(row.getString("slug")).thenReturn("editorial-product");
            when(row.getString("erp_service_id")).thenReturn("17");
            when(row.getString("erp_category_id")).thenReturn("3");
            when(row.getString("payload")).thenReturn(storedPayload.get());
            return List.of(call.<RowMapper<?>>getArgument(1).mapRow(row, 0));
        });
        var repository = spy(new ErpCatalogRepository(jdbc, mapper));
        doNothing().when(repository).reconcile(any());
        doNothing().when(repository).success(any(), anyInt(), anyInt(), anyInt());
        doReturn(new ErpCatalogDtos.SyncStatus("SUCCESS", Instant.now(), Instant.now(), 1, 1, 0)).when(repository).status();
        var client = mock(ErpCatalogClient.class);
        // Same catalogRevision, current ERP configurationVersion=1, persisted MIQA version=0.
        when(client.fetchAvailable()).thenReturn(List.of(contract("1")));
        String available = """
                {"contractVersion":1,"estado":"PRECIO_DISPONIBLE","motivos":[],"catalogRevision":"%s",
                 "configurationVersion":"1","pricingRevision":"scale-revision","evaluatedAt":"2026-10-05T12:00:00Z",
                 "price":{"monto":"73.25","moneda":"PEN","incluyeIgv":true,"alcance":"TOTAL_LINEA",
                 "formaCotizacion":"ESCALA","baseFacturable":{"cantidad":"50","unidad":"unidad"}}}
                """.formatted(contract("1").catalogRevision());
        var outgoingVersions = new ArrayList<String>();
        var outgoingBodies = new ArrayList<String>();
        when(client.evaluatePrice(any())).thenAnswer(call -> {
            String body = mapper.writeValueAsString(call.getArgument(0));
            outgoingBodies.add(body);
            outgoingVersions.add(mapper.readTree(body).get("configurationVersion").asText());
            return new ErpCatalogClient.PricingReply(200, available);
        });
        var publicConfig = new PublicErpConfiguration(jdbc, mapper);
        var validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Set.of());
        var pricing = new PricingService(publicConfig, new ErpPricing(client), validator);
        var input = new PricingDtos.Input("public-product", new BigDecimal("50"), "44", null, Map.of());
        assertThat(publicConfig.configuration("public-product").configurationVersion()).isEqualTo("0");
        assertThat(pricing.evaluate(input).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_STALE);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus(true));
        var sync = new ErpCatalogService(client, repository, transactions);
        sync.synchronize();
        assertThat(mapper.readValue(storedPayload.get(), ErpCatalogContract.class).configurationVersion()).isEqualTo("1");
        assertThat(publicConfig.configuration("public-product").configurationVersion()).isEqualTo("1");
        var result = pricing.evaluate(input);
        assertThat(result.status()).isEqualTo(PricingDtos.Status.PRICE_AVAILABLE);
        assertThat(result.amount()).isEqualTo("73.25");
        assertThat(outgoingVersions).containsExactly("0", "1");
        assertThat(mapper.readTree(outgoingBodies.get(1)).get("catalogRevision").asText()).isEqualTo(contract("0").catalogRevision());
        assertThat(mapper.readTree(outgoingBodies.get(1)).get("cantidad").asText()).isEqualTo("50");
        assertThat(writes).hasValue(1);
        sync.synchronize();
        assertThat(writes).hasValue(1);
        verify(repository).seen(eq("17"), any());
        // Real obsolete versions are still rejected after the fix.
        doReturn(new ErpCatalogClient.PricingReply(200,
                available.replace("\"configurationVersion\":\"1\"", "\"configurationVersion\":\"2\""))).when(client).evaluatePrice(any());
        assertThat(pricing.evaluate(input).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_STALE);
    }
}
