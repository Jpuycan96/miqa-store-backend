package com.miqa.store.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.miqa.store.erp.ErpCatalogDtos.*;

/** JDBC, like Phase 1 persistence, with a dedicated transactional service boundary. */
@Repository
public class ErpCatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ErpCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public boolean trySyncLock() {
        // Transaction-scoped, shared across JVMs; avoids overlapping stale reconciliations.
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(724193820126)", Boolean.class));
    }

    public Map<String, String> revisions() {
        var result = new LinkedHashMap<String, String>();
        jdbc.query("SELECT erp_service_id, catalog_revision FROM erp_catalog_services",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getString(2)));
        return result;
    }

    public void upsert(ErpCatalogContract service, Instant now) {
        jdbc.update("""
                INSERT INTO erp_catalog_services(erp_service_id, catalog_revision, payload, sync_state, last_synced_at)
                VALUES (?, ?, CAST(? AS jsonb), 'AVAILABLE', ?)
                ON CONFLICT (erp_service_id) DO UPDATE SET catalog_revision = EXCLUDED.catalog_revision,
                    payload = EXCLUDED.payload, sync_state = 'AVAILABLE', last_synced_at = EXCLUDED.last_synced_at,
                    updated_at = EXCLUDED.last_synced_at
                """, service.erpServiceId(), service.catalogRevision(), mapper.writeValueAsString(service), Timestamp.from(now));
    }

    public void seen(String id, Instant now) {
        // A lightweight freshness write; unchanged JSON/configuration is never rewritten.
        jdbc.update("""
                UPDATE erp_catalog_services SET last_synced_at = ?, sync_state = 'AVAILABLE',
                    updated_at = CASE WHEN sync_state <> 'AVAILABLE' THEN ? ELSE updated_at END
                WHERE erp_service_id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
    }

    public int missing(String id, Instant now) {
        return jdbc.update("""
                UPDATE erp_catalog_services SET sync_state = 'PENDING_REVALIDATION', updated_at = ?
                WHERE erp_service_id = ? AND sync_state <> 'PENDING_REVALIDATION'
                """, Timestamp.from(now), id);
    }

    public void success(Instant now, int received, int changed, int missing) {
        jdbc.update("""
                UPDATE erp_catalog_sync_status SET outcome = 'SUCCESS', attempted_at = ?, succeeded_at = ?,
                    received = ?, changed = ?, missing = ? WHERE id = 1
                """, Timestamp.from(now), Timestamp.from(now), received, changed, missing);
    }
    public void failure(Instant now, String outcome) {
        jdbc.update("UPDATE erp_catalog_sync_status SET outcome = ?, attempted_at = ?, received = 0, changed = 0, missing = 0 WHERE id = 1",
                outcome, Timestamp.from(now));
    }
    public SyncStatus status() {
        return jdbc.queryForObject("SELECT * FROM erp_catalog_sync_status WHERE id = 1", (rs, row) ->
                new SyncStatus(rs.getString("outcome"), instant(rs, "attempted_at"), instant(rs, "succeeded_at"),
                        rs.getInt("received"), rs.getInt("changed"), rs.getInt("missing")));
    }
    public List<Projection> projections() {
        return jdbc.query("SELECT * FROM erp_catalog_services ORDER BY erp_service_id", (rs, row) ->
                new Projection(rs.getString("erp_service_id"), "AVAILABLE".equals(rs.getString("sync_state")),
                        rs.getString("sync_state"), instant(rs, "last_synced_at"),
                        mapper.readValue(rs.getString("payload"), ErpCatalogContract.class)));
    }
    public boolean lockProduct(String id) {
        return !jdbc.queryForList("SELECT id FROM products WHERE id = ? FOR UPDATE", String.class, id).isEmpty();
    }
    public boolean serviceExists(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM erp_catalog_services WHERE erp_service_id = ?)", Boolean.class, id));
    }
    public void bind(String productId, BindingInput input) {
        jdbc.update("""
                INSERT INTO product_erp_bindings(product_id, erp_service_id, active) VALUES (?, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET erp_service_id = EXCLUDED.erp_service_id,
                    active = EXCLUDED.active, updated_at = current_timestamp
                WHERE product_erp_bindings.erp_service_id IS DISTINCT FROM EXCLUDED.erp_service_id
                   OR product_erp_bindings.active IS DISTINCT FROM EXCLUDED.active
                """, productId, input.erpServiceId(), input.active());
    }
    public Optional<ProductErpBinding> binding(String productId) {
        return jdbc.query("""
                SELECT b.*, s.sync_state, s.last_synced_at FROM product_erp_bindings b
                JOIN erp_catalog_services s USING (erp_service_id) WHERE b.product_id = ?
                """, (rs, row) -> new ProductErpBinding(rs.getString("product_id"), rs.getString("erp_service_id"),
                    rs.getBoolean("active"), rs.getBoolean("active") ? rs.getString("sync_state") : "DISABLED",
                    instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "last_synced_at")), productId).stream().findFirst();
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }
}
