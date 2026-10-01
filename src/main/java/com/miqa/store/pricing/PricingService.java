package com.miqa.store.pricing;

import com.miqa.store.erp.PublicErpConfiguration;
import com.miqa.store.quote.*;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import java.util.List;
import static com.miqa.store.pricing.PricingDtos.*;

@Service
public class PricingService {
    private final PublicErpConfiguration configurations;
    private final ErpPricing pricing;
    private final Validator validator;
    public PricingService(PublicErpConfiguration configurations, ErpPricing pricing, Validator validator) {
        this.configurations=configurations; this.pricing=pricing; this.validator=validator;
    }
    public PublicResult evaluate(Input input) {
        if (input==null || !validator.validate(input).isEmpty()) return Historical.state(Status.CONFIGURATION_INVALID).publicResult();
        var resolved = configurations.resolve(input.productId());
        if (resolved.contract()==null) return Historical.state(Status.CONFIGURATION_STALE).publicResult();
        var contract = resolved.contract();
        var selection = new QuoteV2Dtos.Selection(contract.erpServiceId(),contract.catalogRevision(),contract.configurationVersion(),
                input.erpMaterialId(),input.erpModelId(),input.measures());
        var item = new QuoteV2Dtos.Item(input.productId(),null,null,input.quantity(),null,null,null,List.of(),null,selection);
        QuoteV2Dtos.ErpSnapshot snapshot;
        try { snapshot = ErpQuoteSelection.snapshot(resolved,item); }
        catch (QuoteRequestFailure ex) { return Historical.state(Status.CONFIGURATION_INVALID).publicResult(); }
        return pricing.evaluate(snapshot).publicResult();
    }
}
