package com.miqa.store.pricing;

import com.miqa.store.erp.*;
import com.miqa.store.quote.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.miqa.store.quote.ErpQuoteSelectionTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingServiceTest {
    @Test void reconstructsIdentityAndRejectsInvalidSelectionsBeforeErp() {
        try(var factory = Validation.buildDefaultValidatorFactory()) {
            var config = mock(PublicErpConfiguration.class);
            var erp = mock(ErpPricing.class);
            var service = new PricingService(config,erp,factory.getValidator());
            when(config.resolve("banner")).thenReturn(resolved("M2","SIN_MODELO",false));
            when(erp.evaluate(any())).thenReturn(PricingDtos.Historical.state(PricingDtos.Status.PRICE_AVAILABLE));
            var measures = Map.of("ancho",new BigDecimal("2"),"alto",new BigDecimal("1.5"));
            var valid = new PricingDtos.Input("banner",BigDecimal.ONE,"10",null,measures);
            assertThat(service.evaluate(valid).status()).isEqualTo(PricingDtos.Status.PRICE_AVAILABLE);
            var captor = org.mockito.ArgumentCaptor.forClass(QuoteV2Dtos.ErpSnapshot.class);
            verify(erp).evaluate(captor.capture());
            assertThat(captor.getValue().erpServiceId()).isEqualTo("1");
            assertThat(captor.getValue().catalogRevision()).isEqualTo("a".repeat(64));
            clearInvocations(erp);
            for(var bad : List.of(new PricingDtos.Input("banner",BigDecimal.ONE,"999",null,measures),
                    new PricingDtos.Input("banner",BigDecimal.ONE,"10",null,Map.of()),
                    new PricingDtos.Input("banner",BigDecimal.ZERO,"10",null,measures),
                    new PricingDtos.Input("banner",BigDecimal.ONE,"10","foreign",measures),
                    new PricingDtos.Input("banner",BigDecimal.ONE,"10",null,Map.of("ancho",BigDecimal.ZERO,"alto",BigDecimal.ONE))))
                assertThat(service.evaluate(bad).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_INVALID);
            verifyNoInteractions(erp);
            when(config.resolve("banner")).thenReturn(new PublicErpConfiguration.Resolved("banner","Banner","banner",
                    new PublicErpConfiguration.Configuration("UNAVAILABLE",null,null,null,null),null));
            assertThat(service.evaluate(valid).status()).isEqualTo(PricingDtos.Status.CONFIGURATION_STALE);
            verifyNoInteractions(erp);
        }
    }
}
