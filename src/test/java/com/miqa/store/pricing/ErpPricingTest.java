package com.miqa.store.pricing;

import com.miqa.store.erp.*;
import com.miqa.store.quote.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.miqa.store.quote.ErpQuoteSelectionTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErpPricingTest {
    static final String BODY = """
        {"contractVersion":1,"estado":"PRECIO_DISPONIBLE","motivos":[],"catalogRevision":"%s",
         "configurationVersion":"4","pricingRevision":"price-rev","evaluatedAt":"2026-09-30T12:00:00Z",
         "price":{"monto":"73.25","moneda":"PEN","incluyeIgv":true,"alcance":"TOTAL_LINEA",
                  "formaCotizacion":"M2","baseFacturable":{"cantidad":"3.0","unidad":"M2"}}}
        """.formatted("a".repeat(64));
    static QuoteV2Dtos.ErpSnapshot snapshot() {
        return ErpQuoteSelection.snapshot(resolved("M2","SIN_MODELO",false),item("1","10",null,"1",
                Map.of("ancho",new BigDecimal("2"),"alto",new BigDecimal("1.5"))));
    }
    @Test void mapsCommercialFieldsWithoutTaxCalculationOrPublicRevisions() {
        var client=mock(ErpCatalogClient.class);
        when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(200,BODY));
        var result=new ErpPricing(client).evaluate(snapshot());
        assertThat(result.status()).isEqualTo(PricingDtos.Status.PRICE_AVAILABLE);
        assertThat(result.amount()).isEqualTo("73.25");
        assertThat(result.includesIgv()).isTrue();
        assertThat(result.billableBase().quantity()).isEqualTo("3.0");
        assertThat(result.pricingRevision()).isEqualTo("price-rev");
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result.publicResult());
        assertThat(json).doesNotContain("Revision","configurationVersion","evaluatedAt","apiKey");
        var request=org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(client).evaluatePrice(request.capture());
        var technical=(ErpPricing.Request)request.getValue();
        assertThat(technical.cantidad()).isEqualTo("1");
        assertThat(technical.medidas()).containsEntry("alto","1.5");
    }
    @Test void refusesHttpInsideAnActiveTransaction() {
        var client=mock(ErpCatalogClient.class);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(new ErpPricing(client).evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
            verifyNoInteractions(client);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
    @Test void staleRevisionAndInconsistentHttpStatusCannotBecomePrice() {
        var client=mock(ErpCatalogClient.class); var service=new ErpPricing(client);
        when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(200,BODY.replace("a".repeat(64),"b".repeat(64))));
        assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_STALE);
        when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(409,BODY));
        assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
    }
    @Test void mapsAllStatesAndRejectsInconsistentOrMalformedSuccess() {
        var client=mock(ErpCatalogClient.class); var service=new ErpPricing(client);
        var states=Map.of("POR_COTIZAR",PricingDtos.Status.QUOTE_REQUIRED,
                "CONFIGURACION_OBSOLETA",PricingDtos.Status.CONFIGURATION_STALE,
                "CONFIGURACION_INVALIDA",PricingDtos.Status.CONFIGURATION_INVALID);
        states.forEach((erp,status) -> {
            when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(status.httpStatus(),BODY.replace("PRECIO_DISPONIBLE",erp)));
            var result=service.evaluate(snapshot());
            assertThat(result.status()).isEqualTo(status); assertThat(result.amount()).isNull();
        });
        for(int code:new int[]{401,403,500,502,503,302,429}) {
            when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(code,"secret internal diagnostics"));
            assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
        }
        when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(404,""));
        assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_STALE);
        for(String bad:List.of("{",BODY.replace("73.25","-1"),BODY.replace("PEN","USD"),BODY.replace("true","false"),BODY.replace("TOTAL_LINEA","secret"))) {
            when(client.evaluatePrice(any())).thenReturn(new ErpCatalogClient.PricingReply(200,bad));
            assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
        }
        when(client.evaluatePrice(any())).thenThrow(new IllegalStateException("simulated timeout"));
        assertThat(service.evaluate(snapshot()).status()).isEqualTo(PricingDtos.Status.TEMPORARILY_UNAVAILABLE);
    }
}
