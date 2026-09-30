package com.miqa.store.quote;

import com.miqa.store.erp.PublicErpConfiguration;
import com.miqa.store.error.CatalogNotFoundException;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ErpQuoteSelection {
    private final PublicErpConfiguration configurations;
    public ErpQuoteSelection(PublicErpConfiguration configurations) { this.configurations = configurations; }
    public QuoteV2Dtos.ErpSnapshot snapshot(QuoteV2Dtos.Item item) {
        PublicErpConfiguration.Resolved resolved;
        try { resolved = configurations.resolve(item.productId()); }
        catch (CatalogNotFoundException ex) { throw QuoteRequestFailure.catalogChanged(); }
        return snapshot(resolved, item);
    }
    static QuoteV2Dtos.ErpSnapshot snapshot(PublicErpConfiguration.Resolved resolved, QuoteV2Dtos.Item item) {
        var contract = resolved.contract();
        var selection = item.erp();
        if (contract == null || !"ERP".equals(resolved.publicConfiguration().mode()) || selection == null
                || !Objects.equals(contract.erpServiceId(), selection.erpServiceId())
                || !Objects.equals(contract.catalogRevision(), selection.catalogRevision())
                || !Objects.equals(contract.configurationVersion(), selection.configurationVersion())) throw QuoteRequestFailure.catalogChanged();
        var config = contract.configuracion();
        var quantity = item.quantity();
        var rules = config.cantidad();
        if (quantity == null || quantity.compareTo(new BigDecimal(rules.minimo())) < 0
                || quantity.compareTo(new BigDecimal(rules.maximo())) > 0
                || quantity.stripTrailingZeros().scale() > (rules.permiteDecimales() ? rules.precision() : 0)
                || rules.multiploObligatorio() != null && quantity.remainder(new BigDecimal(rules.multiploObligatorio())).signum() != 0)
            throw QuoteRequestFailure.invalid();
        var required = new HashSet<>(config.medidas().camposRequeridos());
        required.remove("cantidad");
        if (selection.measures() == null || !required.equals(selection.measures().keySet())) throw QuoteRequestFailure.invalid();
        for (var value : selection.measures().values()) {
            // ERP v1 exposes fields/unit, not dimensional bounds. MIQA transport limits are explicit.
            if (value == null || value.compareTo(new BigDecimal("0.01")) < 0 || value.compareTo(new BigDecimal("1000")) > 0
                    || value.stripTrailingZeros().scale() > 6) throw QuoteRequestFailure.invalid();
        }
        var material = config.materiales().stream().filter(m -> m.erpMaterialId().equals(selection.erpMaterialId()))
                .findFirst().orElseThrow(QuoteRequestFailure::catalogChanged);
        var model = selection.erpModelId() == null ? null : material.modelos().stream()
                .filter(m -> m.erpModelId().equals(selection.erpModelId())).findFirst().orElseThrow(QuoteRequestFailure::catalogChanged);
        if (material.modoModelos().equals("SIN_MODELO") ? model != null : model == null) throw QuoteRequestFailure.invalid();
        return new QuoteV2Dtos.ErpSnapshot(2, resolved.productId(), resolved.productName(), resolved.productSlug(),
                contract.erpServiceId(), contract.nombreReferencia(), contract.categoria(), contract.catalogRevision(),
                contract.configurationVersion(), material.erpMaterialId(), material.nombreReferencia(),
                model == null ? null : model.erpModelId(), model == null ? null : model.nombreReferencia(), quantity,
                new TreeMap<>(selection.measures()), config, item.notes());
    }
}
