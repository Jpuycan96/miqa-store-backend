package com.miqa.store.quote;

import com.miqa.store.erp.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ErpQuoteSelectionTest {
    static PublicErpConfiguration.Resolved resolved(String form, String models, boolean decimals) {
        var fields = switch(form) { case "M2" -> List.of("ancho", "alto", "cantidad"); case "METRO_LINEAL" -> List.of("longitud", "cantidad"); default -> List.of("cantidad"); };
        var config = new ErpCatalogContract.Configuration(new ErpCatalogContract.Quantity("unidad", decimals ? "0.5" : "1", "1",
                decimals ? "0.5" : null, decimals, decimals ? 2 : 0, "100"), "FIJO", form,
                new ErpCatalogContract.Measurements(form.equals("M2") ? "SUPERFICIE" : form.equals("ESCALA") ? "NINGUNA" : "LONGITUD",
                        form.equals("ESCALA") ? null : "m", fields),
                List.of(new ErpCatalogContract.Material("10", "Material histórico", models,
                        models.equals("SIN_MODELO") ? List.of() : List.of(new ErpCatalogContract.Model("20", "Modelo histórico")))));
        var contract = new ErpCatalogContract(1,"ERP_GIGANTOGRAFIAS","1","Servicio histórico",new ErpCatalogContract.Category("3","Categoría ERP"),
                true,true,"CONFIGURADA",List.of(),"4","a".repeat(64),Instant.now(),config);
        return new PublicErpConfiguration.Resolved("banner","Banner editorial","banner",
                new PublicErpConfiguration.Configuration("ERP","1",contract.catalogRevision(),"4",config),contract);
    }
    static QuoteV2Dtos.Item item(String service, String material, String model, String quantity, Map<String,BigDecimal> measures) {
        return new QuoteV2Dtos.Item("banner",null,null,new BigDecimal(quantity),null,null,null,List.of(),"Nota",
                new QuoteV2Dtos.Selection(service,"a".repeat(64),"4",material,model,measures));
    }
    @Test void m2SnapshotPreservesAuthoritativeNamesAndConfiguration() {
        var snapshot = ErpQuoteSelection.snapshot(resolved("M2","SELECCION",false),item("1","10","20","5",Map.of("ancho",new BigDecimal("2.5"),"alto",BigDecimal.ONE)));
        assertThat(snapshot.schemaVersion()).isEqualTo(2);
        assertThat(snapshot.productName()).isEqualTo("Banner editorial");
        assertThat(snapshot.materialName()).isEqualTo("Material histórico");
        assertThat(snapshot.modelName()).isEqualTo("Modelo histórico");
        assertThat(snapshot.configuration().medidas().camposRequeridos()).containsExactly("ancho","alto","cantidad");
        assertThat(snapshot.catalogRevision()).isEqualTo("a".repeat(64));
    }
    @Test void escalaHasNoMeasuresAndLinealRequiresOnlyContractFields() {
        assertThat(ErpQuoteSelection.snapshot(resolved("ESCALA","SIN_MODELO",false),item("1","10",null,"5",Map.of())).measures()).isEmpty();
        assertThat(ErpQuoteSelection.snapshot(resolved("METRO_LINEAL","FIJO",false),item("1","10","20","5",Map.of("longitud",new BigDecimal("1.25")))).measures()).containsKey("longitud");
    }
    @Test void allowsDecimalMultiplesButNotSuggestedStepRestriction() {
        var source = resolved("ESCALA","SIN_MODELO",true);
        assertThat(ErpQuoteSelection.snapshot(source,item("1","10",null,"1.5",Map.of())).quantity()).isEqualByComparingTo("1.5");
        for (String quantity : List.of("0.25","0","1.25","101","1.001"))
            assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,item("1","10",null,quantity,Map.of()))).isInstanceOf(QuoteRequestFailure.class);
    }
    @Test void rejectsForeignServiceMaterialAndModel() {
        var source = resolved("ESCALA","FIJO",false);
        for (var item : List.of(item("999","10","20","1",Map.of()),item("1","999","20","1",Map.of()),
                item("1","10","999","1",Map.of()),item("1","10",null,"1",Map.of())))
            assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,item)).isInstanceOf(QuoteRequestFailure.class);
    }
    @Test void rejectsMissingAdditionalInvalidAndOverpreciseMeasures() {
        var source = resolved("METRO_LINEAL","SIN_MODELO",false);
        for (Map<String,BigDecimal> measures : List.of(Map.<String,BigDecimal>of(),Map.of("ancho",BigDecimal.ONE),
                Map.of("longitud",BigDecimal.ONE,"alto",BigDecimal.ONE),Map.of("longitud",new BigDecimal("0.001")),
                Map.of("longitud",new BigDecimal("1001")),Map.of("longitud",new BigDecimal("1.0000001"))))
            assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,item("1","10",null,"1",measures))).isInstanceOf(QuoteRequestFailure.class);
    }
    @Test void rejectsDecimalsForIntegerContractAndModelsWhenNotAllowed() {
        var source = resolved("ESCALA","SIN_MODELO",false);
        assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,item("1","10",null,"1.5",Map.of()))).isInstanceOf(QuoteRequestFailure.class);
        assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,item("1","10","20","1",Map.of()))).isInstanceOf(QuoteRequestFailure.class);
    }
    @Test void unavailableAndChangedRevisionConflict() {
        var source = resolved("ESCALA","SIN_MODELO",false);
        var disabled = new PublicErpConfiguration.Resolved("banner","Banner","banner",
                new PublicErpConfiguration.Configuration("UNAVAILABLE",null,null,null,null),null);
        assertThatThrownBy(() -> ErpQuoteSelection.snapshot(disabled,item("1","10",null,"1",Map.of()))).isInstanceOf(QuoteRequestFailure.class);
        var stale = new QuoteV2Dtos.Item("banner",null,null,BigDecimal.ONE,null,null,null,List.of(),null,
                new QuoteV2Dtos.Selection("1","b".repeat(64),"4","10",null,Map.of()));
        assertThatThrownBy(() -> ErpQuoteSelection.snapshot(source,stale)).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(409));
    }
}
