package com.miqa.store.erp;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit TEST-only integration after V10. No Boot, Flyway, DDL or ERP HTTP. Every fixture rolls back. */
class ErpEditorialPersistenceIT {
    private Connection connection;
    private JdbcTemplate jdbc;
    private ErpCatalogRepository repository;
    private PublicErpConfiguration publicCatalog;
    private String erpId, categoryId, suffix;
    private ErpCatalogContract service(String revision, String categoryName) {
        return new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON
                .replace("\"erpServiceId\":\"17\"", "\"erpServiceId\":\"" + erpId + "\"")
                .replace("\"erpCategoryId\":\"3\"", "\"erpCategoryId\":\"" + categoryId + "\"")
                .replace("SELLOS", "Synthetic service " + suffix).replace("ERP category", categoryName)
                .replace("a".repeat(64), revision) + "]").getFirst();
    }
    @BeforeEach void setup() throws Exception {
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db", "miqa_store_local", password);
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("miqa_store_test_db");
        var mapper = JsonMapper.builder().build();
        repository = new ErpCatalogRepository(jdbc, mapper);
        publicCatalog = new PublicErpConfiguration(jdbc, mapper);
        suffix = UUID.randomUUID().toString();
        erpId = new java.math.BigInteger(suffix.replace("-", ""), 16).toString();
        categoryId = new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString();
        assertThat(repository.trySyncLock()).isTrue();
    }
    @AfterEach void rollback() throws Exception {
        if (connection != null) try { connection.rollback(); } finally { connection.close(); }
    }
    private String reconcile(ErpCatalogContract contract) {
        repository.upsert(contract, Instant.now()); repository.reconcile(contract);
        return jdbc.queryForObject("SELECT product_id FROM product_erp_bindings WHERE erp_service_id = ? AND canonical", String.class, erpId);
    }
    @Test void createRepeatChangeRetireAndRepublishKeepIdentityAndEditorialContent() {
        var first = service("a".repeat(64), "Synthetic category " + suffix);
        String id = reconcile(first);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(publicCatalog.visibleProductIds()).doesNotContain(id);
        jdbc.update("UPDATE products SET published=true, description='Preserved editorial', seo_title='SEO', featured=true WHERE id=?", id);
        jdbc.update("INSERT INTO product_images(id,product_id,url,alt_text,primary_image) VALUES (?,?,'/synthetic.png','Synthetic',true)", suffix, id);
        var original = jdbc.queryForMap("SELECT id,slug,description,seo_title,featured FROM products WHERE id=?", id);
        var cat = jdbc.queryForMap("SELECT id,slug FROM categories WHERE erp_category_id=?", categoryId);
        assertThat(publicCatalog.resolve(id).contract().erpServiceId()).isEqualTo(erpId);
        repository.seen(erpId, Instant.now()); repository.reconcile(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE erp_service_id=?", Integer.class, erpId)).isEqualTo(1);
        assertThat(reconcile(service("b".repeat(64), "Renamed category " + suffix))).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT id,slug FROM categories WHERE erp_category_id=?", categoryId)).isEqualTo(cat);
        assertThat(jdbc.queryForObject("SELECT name FROM categories WHERE erp_category_id=?", String.class, categoryId)).startsWith("Renamed");
        repository.missing(erpId, Instant.now());
        assertThat(publicCatalog.visibleProductIds()).doesNotContain(id);
        repository.seen(erpId, Instant.now()); repository.reconcile(first);
        assertThat(publicCatalog.visibleProductIds()).contains(id);
        assertThat(jdbc.queryForMap("SELECT id,slug,description,seo_title,featured FROM products WHERE id=?", id)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_images WHERE product_id=?", Integer.class, id)).isEqualTo(1);
    }
    @Test void oldProjectionAndMultipleHistoricalBindingsNeverCauseAdoptionOrSlugOverwrite() {
        var contract = service("a".repeat(64), "Synthetic category " + suffix);
        repository.upsert(contract, Instant.now());
        String legacyCategory = "lc-" + suffix;
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES (?,'Legacy',?)", legacyCategory, legacyCategory);
        String occupied = "synthetic-service-" + suffix;
        for (String id : List.of("old-a-" + suffix, "old-b-" + suffix)) {
            jdbc.update("INSERT INTO products(id,category_id,name,slug,short_description,description,sale_type,unit_label,published) VALUES (?,?,'Legacy',?,'','History','QUANTITY','unit',true)",
                    id, legacyCategory, id.startsWith("old-a") ? occupied : id);
            repository.bind(id, new ErpCatalogDtos.BindingInput(erpId, true));
        }
        // Unchanged revision must still create the missing editorial projection.
        repository.seen(erpId, Instant.now()); repository.reconcile(contract);
        String principal = jdbc.queryForObject("SELECT product_id FROM product_erp_bindings WHERE erp_service_id=? AND canonical", String.class, erpId);
        assertThat(UUID.fromString(principal)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id=?", String.class, principal)).isEqualTo(occupied + "-1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE erp_service_id=?", Integer.class, erpId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT description FROM products WHERE slug=?", String.class, occupied)).isEqualTo("History");
        assertThat(publicCatalog.visibleProductIds()).doesNotContain("old-a-" + suffix, "old-b-" + suffix);
    }
    @Test void inactiveCanonicalRetainsUniqueIdentityAndCannotBeReassigned() throws Exception {
        var contract = service("a".repeat(64), "Synthetic category " + suffix);
        String principal = reconcile(contract);
        repository.bind(principal, new ErpCatalogDtos.BindingInput(erpId, false));
        repository.reconcile(contract);
        assertThat(repository.binding(principal).orElseThrow().canonical()).isTrue();
        assertThat(repository.binding(principal).orElseThrow().active()).isFalse();
        assertThatThrownBy(() -> repository.bind(principal, new ErpCatalogDtos.BindingInput("1", true))).isInstanceOf(com.miqa.store.admin.AdminFailure.class);
        String sibling = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO products(id,category_id,name,slug,short_description,description,catalog_mode) SELECT ?,category_id,'Sibling',?,'','','ERP' FROM products WHERE id=?", sibling, sibling, principal);
        Savepoint point = connection.setSavepoint();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO product_erp_bindings(product_id,erp_service_id,canonical) VALUES (?,?,true)", sibling, erpId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("uq_product_erp_canonical");
        connection.rollback(point);
    }
    @Test void serviceCategoryMoveUsesErpIdentityWithoutChangingProductSlug() {
        String principal = reconcile(service("a".repeat(64), "Original category " + suffix));
        String slug = jdbc.queryForObject("SELECT slug FROM products WHERE id=?", String.class, principal);
        String oldCategory = categoryId;
        categoryId = new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString();
        reconcile(service("b".repeat(64), "Other category " + suffix));
        assertThat(jdbc.queryForObject("SELECT c.erp_category_id FROM products p JOIN categories c ON c.id=p.category_id WHERE p.id=?", String.class, principal)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id=?", String.class, principal)).isEqualTo(slug);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE erp_category_id=?", Integer.class, oldCategory)).isEqualTo(1);
    }
    @Test void synchronizationLeavesBothHistoricalSnapshotTablesUnchanged() {
        var mapper = JsonMapper.builder().build();
        var requests = new com.miqa.store.quote.QuoteRequestRepository(jdbc, mapper);
        var input = new com.miqa.store.quote.QuoteRequestDtos.Submission(
                new com.miqa.store.quote.QuoteRequestDtos.Contact("Synthetic", "+51999999999", null), null, List.of());
        var snapshot = new com.miqa.store.quote.QuoteSnapshot(1, "historical-" + suffix, "Historical title", "historical-url",
                new com.miqa.store.quote.QuoteSnapshot.Category("historical-category", "Historical category", "historical-category"),
                com.miqa.store.catalog.ProductSaleType.QUANTITY, 5L, "unit", null, null, null, null, null, null, List.of(),
                new com.miqa.store.quote.QuoteSnapshot.Rules(1, 1, 1000000000L, false, null, null, null), null);
        var key1 = UUID.randomUUID(); var key2 = UUID.randomUUID();
        requests.insert(new com.miqa.store.quote.QuoteRequestCanonicalizer.Canonical(key1, "a".repeat(64), input), List.of(snapshot));
        requests.insertV2(new com.miqa.store.quote.QuoteRequestCanonicalizer.Canonical(key2, "b".repeat(64), input),
                List.of(new com.miqa.store.quote.QuoteV2Dtos.StoredItem(snapshot.productId(), snapshot)));
        String query = "SELECT snapshot::text FROM quote_request_items WHERE product_id=? UNION ALL SELECT snapshot::text FROM quote_request_v2_items WHERE product_id=?";
        var before = jdbc.queryForList(query, String.class, snapshot.productId(), snapshot.productId());
        reconcile(service("a".repeat(64), "Synthetic category " + suffix));
        repository.missing(erpId, Instant.now());
        assertThat(jdbc.queryForList(query, String.class, snapshot.productId(), snapshot.productId())).containsExactlyElementsOf(before);
        assertThat(requests.find(key1)).isPresent(); assertThat(requests.find(key2)).isPresent();
    }

}
