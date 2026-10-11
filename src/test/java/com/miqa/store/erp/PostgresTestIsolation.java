package com.miqa.store.erp;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Read-only preflight; never creates/migrates schema or opens another destination. */
public final class PostgresTestIsolation {
    private PostgresTestIsolation() {}
    public static void verify(Connection connection) throws SQLException {
        connection.setSchema("public");
        connection.setReadOnly(true);
        try (var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT current_database(),current_user,session_user,host(inet_server_addr()),inet_server_port(),current_setting('data_directory'),current_schema(),current_setting('search_path')")) {
                if (!row.next() || !"miqa_store_test_db".equals(row.getString(1))
                    || !"miqa_store_local".equals(row.getString(2)) || !"miqa_store_local".equals(row.getString(3))
                    || !"127.0.0.1".equals(row.getString(4)) || row.getInt(5) != 55432
                    || !"d:/miqa-store/miqa-store-backend/.local/postgres-test/data".equals(row.getString(6).replace('\\','/').toLowerCase(Locale.ROOT))
                    || !"public".equals(row.getString(7)) || !"public".equals(row.getString(8)))
                    throw new IllegalStateException("Unexpected PostgreSQL TEST identity/schema; no writes allowed");
            }
            for (String query : List.of(
                "SELECT id,erp_category_id,name,slug,description,catalog_headline,catalog_description,active,display_order FROM public.categories WHERE false",
                "SELECT id,category_id,name,slug,short_description,description,catalog_mode,sale_type,unit_label,published,featured,seo_title FROM public.products WHERE false",
                "SELECT product_id,erp_service_id,active,canonical,created_at,updated_at FROM public.product_erp_bindings WHERE false",
                "SELECT erp_service_id,catalog_revision,payload,sync_state,last_synced_at,created_at,updated_at FROM public.erp_catalog_services WHERE false",
                "SELECT id,outcome,attempted_at,succeeded_at,received,changed,missing FROM public.erp_catalog_sync_status WHERE false",
                "SELECT slug,category_id FROM public.category_slug_aliases WHERE false",
                "SELECT id,product_id,url,alt_text,primary_image,active FROM public.product_images WHERE false",
                "SELECT id,idempotency_key,request_hash FROM public.quote_requests WHERE false",
                "SELECT product_id,snapshot FROM public.quote_request_items WHERE false",
                "SELECT product_id,snapshot FROM public.quote_request_v2_items WHERE false")) statement.executeQuery(query).close();
            try (var row = statement.executeQuery("SELECT count(*) FROM public.flyway_schema_history WHERE success AND version IN ('5','8','9','10')")) {
                row.next(); if (row.getInt(1) != 4) throw new IllegalStateException("TEST schema prerequisite missing; migrations forbidden");
            }
            try (var row = statement.executeQuery("SELECT count(*) FROM pg_index WHERE indexrelid IN ('public.uq_categories_erp_identity'::regclass,'public.uq_product_erp_canonical'::regclass) AND indisunique AND indisvalid AND indisready")) {
                row.next(); if (row.getInt(1) != 2) throw new IllegalStateException("TEST identity indexes missing");
            }
        }
        // End read-only preflight before opening the rollback-only fixture transaction.
        connection.setReadOnly(false);
        connection.setAutoCommit(false);
    }
    static void fixturesAbsent(JdbcTemplate jdbc, String service, String category, String suffix) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erp_catalog_services WHERE erp_service_id=?",Integer.class,service)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE erp_service_id=?",Integer.class,service)).isZero();
        categoryAbsent(jdbc, category);
        for (String query : List.of(
            "SELECT count(*) FROM products WHERE id LIKE ? OR slug LIKE ?",
            "SELECT count(*) FROM categories WHERE id LIKE ? OR slug LIKE ?"))
            assertThat(jdbc.queryForObject(query,Integer.class,"%"+suffix,"%"+suffix)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_slug_aliases WHERE slug LIKE ?",Integer.class,"%"+suffix)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE id=?",Integer.class,suffix)).isZero();
        for(String table:List.of("quote_request_items","quote_request_v2_items"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE product_id=?",Integer.class,"historical-"+suffix)).isZero();
    }
    static void categoryAbsent(JdbcTemplate jdbc, String category) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE erp_category_id=?",Integer.class,category)).isZero();
    }
}
