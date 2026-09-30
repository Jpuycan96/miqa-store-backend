package com.miqa.store.erp;

import java.time.Instant;
import java.util.List;

/** Local allowlist of ERP v1 fields. No prices, credentials or persistence entities. */
public record ErpCatalogContract(
        Integer contractVersion, String sourceSystem, String erpServiceId, String nombreReferencia,
        Category categoria, Boolean elegible, Boolean disponible, String estadoConfiguracion,
        List<String> motivos, String configurationVersion, String catalogRevision,
        Instant evaluatedAt, Configuration configuracion) {
    public record Category(String erpCategoryId, String nombreReferencia) {}
    public record Quantity(String unidad, String minimo, String incrementoSugerido,
            String multiploObligatorio, Boolean permiteDecimales, Integer precision, String maximo) {}
    public record Measurements(String modo, String unidad, List<String> camposRequeridos) {}
    public record Configuration(Quantity cantidad, String modoMaterial, String formaCotizacion,
            Measurements medidas, List<Material> materiales) {}
    public record Material(String erpMaterialId, String nombreReferencia, String modoModelos, List<Model> modelos) {}
    public record Model(String erpModelId, String nombreReferencia) {}
}
