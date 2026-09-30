package com.miqa.store.erp;

import com.miqa.store.error.CatalogNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** Only the local projection is read. This service has no ERP client or credentials. */
@Service
public class PublicErpConfiguration {
    public record Configuration(String mode, String erpServiceId, String catalogRevision,
            String configurationVersion, ErpCatalogContract.Configuration configuration) {
        static Configuration state(String mode) { return new Configuration(mode, null, null, null, null); }
    }
    public record Resolved(String productId, String productName, String productSlug,
                           Configuration publicConfiguration, ErpCatalogContract contract) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public PublicErpConfiguration(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Configuration configuration(String productId) { return resolve(productId).publicConfiguration(); }
    public Resolved resolve(String productId) {
        var rows = jdbc.query("""
                SELECT p.id, p.name, p.slug, b.product_id AS bound_product, b.active, s.erp_service_id,
                       s.sync_state, s.payload::text AS payload
                FROM products p JOIN categories c ON c.id = p.category_id
                LEFT JOIN product_erp_bindings b ON b.product_id = p.id
                LEFT JOIN erp_catalog_services s ON s.erp_service_id = b.erp_service_id
                WHERE p.id = ? AND p.published AND c.active
                """, (rs, row) -> {
            Configuration config = Configuration.state(rs.getString("bound_product") == null ? "LEGACY" : "UNAVAILABLE");
            ErpCatalogContract contract = null;
            if (rs.getBoolean("active") && "AVAILABLE".equals(rs.getString("sync_state"))) {
                try {
                    var candidate = mapper.readValue(rs.getString("payload"), ErpCatalogContract.class);
                    if (Objects.equals(candidate.erpServiceId(), rs.getString("erp_service_id")) && supported(candidate)) {
                        contract = candidate;
                        config = new Configuration("ERP", contract.erpServiceId(), contract.catalogRevision(),
                                contract.configurationVersion(), contract.configuracion());
                    }
                } catch (RuntimeException ignored) { /* Corrupt/unsupported projection fails closed. */ }
            }
            return new Resolved(rs.getString("id"), rs.getString("name"), rs.getString("slug"), config, contract);
        }, productId);
        if (rows.isEmpty()) throw new CatalogNotFoundException("Producto no disponible");
        return rows.getFirst();
    }

    public static boolean supported(ErpCatalogContract contract) {
        try {
            ErpCatalogValidation.listing(List.of(contract));
            var config = contract.configuracion();
            var q = config.cantidad();
            if (q.precision() > 6 || !q.permiteDecimales() && q.precision() != 0
                    || new BigDecimal(q.minimo()).compareTo(new BigDecimal(q.maximo())) > 0) return false;
            var fields = config.medidas().camposRequeridos();
            if (fields.size() != new HashSet<>(fields).size() || !fields.contains("cantidad")) return false;
            if (!Set.of("cantidad", "ancho", "alto", "longitud").containsAll(fields)) return false;
            String mode = config.medidas().modo();
            if (!(switch (config.formaCotizacion()) {
                case "ESCALA" -> mode.equals("NINGUNA") && fields.equals(List.of("cantidad"));
                case "M2" -> mode.equals("SUPERFICIE") && new HashSet<>(fields).equals(Set.of("cantidad", "ancho", "alto"));
                case "METRO_LINEAL" -> mode.equals("LONGITUD") && new HashSet<>(fields).equals(Set.of("cantidad", "longitud"));
                default -> false;
            })) return false;
            if (!mode.equals("NINGUNA") && !"m".equals(config.medidas().unidad())) return false;
            if (config.modoMaterial().equals("FIJO") && config.materiales().size() != 1) return false;
            for (var m : config.materiales()) {
                if (m.modoModelos().equals("SIN_MODELO") && !m.modelos().isEmpty()
                        || m.modoModelos().equals("FIJO") && m.modelos().size() != 1
                        || m.modoModelos().equals("SELECCION") && m.modelos().isEmpty()) return false;
            }
            return true;
        } catch (RuntimeException ex) { return false; }
    }
}
