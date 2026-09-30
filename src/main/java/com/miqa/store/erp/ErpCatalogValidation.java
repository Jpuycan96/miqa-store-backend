package com.miqa.store.erp;

import java.math.BigDecimal;
import java.util.*;

final class ErpCatalogValidation {
    private ErpCatalogValidation() {}

    /** Validate the entire unpaginated v1 listing before any projection mutation. */
    static void listing(List<ErpCatalogContract> services) {
        try {
            require(services != null);
            var ids = new HashSet<String>();
            for (var s : services) {
                require(s != null && Integer.valueOf(1).equals(s.contractVersion()));
                require("ERP_GIGANTOGRAFIAS".equals(s.sourceSystem()));
                require(id(s.erpServiceId()) && ids.add(s.erpServiceId()));
                require(text(s.nombreReferencia()) && Boolean.TRUE.equals(s.elegible()) && Boolean.TRUE.equals(s.disponible()));
                require("CONFIGURADA".equals(s.estadoConfiguracion()) && s.motivos() != null && s.motivos().isEmpty());
                require(text(s.configurationVersion()) && s.catalogRevision() != null && s.catalogRevision().matches("[0-9a-f]{64}"));
                require(s.evaluatedAt() != null);
                require(s.categoria() == null || id(s.categoria().erpCategoryId()) && text(s.categoria().nombreReferencia()));
                var c = s.configuracion();
                require(c != null && c.cantidad() != null && c.medidas() != null && c.materiales() != null);
                var q = c.cantidad();
                require(text(q.unidad()) && positive(q.minimo()) && positive(q.incrementoSugerido()) && positive(q.maximo()));
                require(q.multiploObligatorio() == null || positive(q.multiploObligatorio()));
                require(q.permiteDecimales() != null && q.precision() != null && q.precision() >= 0);
                require(Set.of("FIJO", "SELECCION").contains(c.modoMaterial()));
                require(Set.of("ESCALA", "M2", "METRO_LINEAL").contains(c.formaCotizacion()));
                require(Set.of("NINGUNA", "SUPERFICIE", "LONGITUD").contains(c.medidas().modo()));
                require(c.medidas().camposRequeridos() != null && !c.medidas().camposRequeridos().isEmpty());
                require(c.medidas().camposRequeridos().stream().allMatch(ErpCatalogValidation::text));
                require(!c.materiales().isEmpty());
                var materials = new HashSet<String>();
                for (var m : c.materiales()) {
                    require(m != null && id(m.erpMaterialId()) && materials.add(m.erpMaterialId()) && text(m.nombreReferencia()));
                    require(Set.of("SIN_MODELO", "FIJO", "SELECCION").contains(m.modoModelos()) && m.modelos() != null);
                    var models = new HashSet<String>();
                    for (var model : m.modelos()) require(model != null && id(model.erpModelId())
                            && models.add(model.erpModelId()) && text(model.nombreReferencia()));
                }
            }
        } catch (Exception ex) {
            throw new ErpCatalogFailure("INVALID_CONTRACT");
        }
    }

    static boolean id(String value) { return value != null && value.length() <= 64 && value.matches("[1-9][0-9]*"); }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean positive(String value) { return text(value) && new BigDecimal(value).signum() > 0; }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
}
