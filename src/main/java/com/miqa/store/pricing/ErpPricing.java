package com.miqa.store.pricing;

import com.miqa.store.erp.ErpCatalogClient;
import com.miqa.store.quote.QuoteV2Dtos;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.pricing.PricingDtos.*;

/** Allowlisted commercial response; upstream diagnostics are never forwarded or logged. */
@Component
public class ErpPricing {
    private final ErpCatalogClient client;
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ErpPricing(ErpCatalogClient client) { this.client=client; }
    record Request(int contractVersion, String erpServiceId, String erpMaterialId, String erpModelId,
            String cantidad, Map<String,String> medidas, String catalogRevision, String configurationVersion) {}
    record Base(String cantidad, String unidad) {}
    record Price(String monto, String moneda, Boolean incluyeIgv, String alcance, String formaCotizacion, Base baseFacturable) {}
    record Reply(int contractVersion, String estado, List<String> motivos, String catalogRevision,
            String configurationVersion, String pricingRevision, Instant evaluatedAt, Price price) {}
    public Historical evaluate(QuoteV2Dtos.ErpSnapshot snapshot) {
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Pricing HTTP requires no local transaction");
            var measures = new TreeMap<String,String>();
            snapshot.measures().forEach((key,value) -> measures.put(key,value.toPlainString()));
            var response = client.evaluatePrice(new Request(1,snapshot.erpServiceId(),snapshot.erpMaterialId(),snapshot.erpModelId(),
                    snapshot.quantity().toPlainString(),measures,snapshot.catalogRevision(),snapshot.configurationVersion()));
            if (response.status()==404) return Historical.state(Status.CONFIGURATION_STALE);
            if (!Set.of(200,409,422).contains(response.status())) return Historical.state(Status.TEMPORARILY_UNAVAILABLE);
            var reply = mapper.readValue(response.body(),Reply.class);
            if (reply.contractVersion()!=1) throw new IllegalArgumentException();
            Status state = switch(reply.estado()) {
                case "PRECIO_DISPONIBLE" -> Status.PRICE_AVAILABLE;
                case "POR_COTIZAR" -> Status.QUOTE_REQUIRED;
                case "CONFIGURACION_OBSOLETA" -> Status.CONFIGURATION_STALE;
                case "CONFIGURACION_INVALIDA" -> Status.CONFIGURATION_INVALID;
                default -> throw new IllegalArgumentException();
            };
            if (response.status()!=state.httpStatus()) throw new IllegalArgumentException();
            if (state==Status.CONFIGURATION_STALE || state==Status.CONFIGURATION_INVALID) return Historical.state(state);
            if (!Objects.equals(snapshot.catalogRevision(),reply.catalogRevision())
                    || !Objects.equals(snapshot.configurationVersion(),reply.configurationVersion())) return Historical.state(Status.CONFIGURATION_STALE);
            if (reply.evaluatedAt()==null) throw new IllegalArgumentException();
            if (reply.pricingRevision()!=null && !reply.pricingRevision().matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException();
            if (state==Status.QUOTE_REQUIRED) return new Historical(state,null,null,null,null,null,null,reply.pricingRevision(),reply.evaluatedAt());
            var p = reply.price();
            String expectedUnit = switch(snapshot.configuration().formaCotizacion()) {
                case "M2" -> "M2"; case "METRO_LINEAL" -> "METRO_LINEAL"; default -> snapshot.configuration().cantidad().unidad();
            };
            if (p==null || !decimal(p.monto(),true) || !"PEN".equals(p.moneda()) || !Boolean.TRUE.equals(p.incluyeIgv())
                    || !"TOTAL_LINEA".equals(p.alcance()) || !snapshot.configuration().formaCotizacion().equals(p.formaCotizacion())
                    || reply.pricingRevision()==null || !reply.pricingRevision().matches("[A-Za-z0-9._:-]{1,128}")
                    || p.baseFacturable()==null || !decimal(p.baseFacturable().cantidad(),false)
                    || !Objects.equals(expectedUnit,p.baseFacturable().unidad())) throw new IllegalArgumentException();
            return new Historical(state,p.monto(),p.moneda(),p.incluyeIgv(),p.alcance(),p.formaCotizacion(),
                    new BillableBase(p.baseFacturable().cantidad(),p.baseFacturable().unidad()),reply.pricingRevision(),reply.evaluatedAt());
        } catch (RuntimeException ex) { return Historical.state(Status.TEMPORARILY_UNAVAILABLE); }
    }
    private static boolean decimal(String value, boolean zeroAllowed) {
        return value!=null && value.matches("[0-9]{1,20}(\\.[0-9]{1,10})?")
                && (zeroAllowed || new BigDecimal(value).signum()>0);
    }
}
