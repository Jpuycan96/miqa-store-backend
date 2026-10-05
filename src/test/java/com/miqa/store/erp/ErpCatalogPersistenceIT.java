package com.miqa.store.erp;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.miqa.store.erp.ErpCatalogDtos.*;

/** Explicit -Dtest=ErpCatalogPersistenceIT only AFTER manual V10 application in TEST.
 * No Boot/Flyway/DDL. Fixed TEST destination, synthetic fixtures, rollback after every test. */
class ErpCatalogPersistenceIT {
    private Connection connection;
    private JdbcTemplate jdbc;
    private ErpCatalogRepository repository;
    private String productA;
    private String productB;
    private String erpId;

    @BeforeEach void setup() throws Exception {
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("TEST_DB_PASSWORD required");
        connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/miqa_store_test_db",
                "miqa_store_local", password);
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        repository = new ErpCatalogRepository(jdbc, JsonMapper.builder().build());
        String suffix = UUID.randomUUID().toString();
        String category = "erp-it-" + suffix;
        productA = "erp-a-" + suffix;
        productB = "erp-b-" + suffix;
        erpId = new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString();
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES (?, 'Synthetic ERP test', ?)", category, category);
        for (String product : new String[]{productA, productB}) jdbc.update("""
                INSERT INTO products(id,category_id,name,slug,short_description,description,sale_type,unit_label)
                VALUES (?, ?, 'Editorial publication', ?, 'Short', 'Editorial content', 'QUANTITY', 'unidad')
                """, product, category, product);
        var contract = new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON
                .replace("\"erpServiceId\":\"17\"", "\"erpServiceId\":\"" + erpId + "\"") + "]").getFirst();
        repository.upsert(contract, Instant.now());
    }
    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }
    @Test void manyProductsCanShareServiceButDatabaseRejectsContradictoryBindings() throws Exception {
        repository.bind(productA, new BindingInput(erpId, true));
        repository.bind(productB, new BindingInput(erpId, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_erp_bindings WHERE erp_service_id = ?", Integer.class, erpId)).isEqualTo(2);
        Savepoint point = connection.setSavepoint();
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement("INSERT INTO product_erp_bindings(product_id,erp_service_id) VALUES (?,?)")) {
                statement.setString(1, productA);
                statement.setString(2, erpId);
                statement.executeUpdate();
            }
        }).isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23505"));
        connection.rollback(point);
        repository.bind(productA, new BindingInput(erpId, false));
        assertThat(repository.binding(productA).orElseThrow().state()).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT description FROM products WHERE id = ?", String.class, productA)).isEqualTo("Editorial content");
    }
    @Test void missingRetainsPayloadAndBindingsAndReappearanceRestoresAvailability() {
        repository.bind(productA, new BindingInput(erpId, true));
        String payload = jdbc.queryForObject("SELECT payload::text FROM erp_catalog_services WHERE erp_service_id = ?", String.class, erpId);
        assertThat(repository.missing(erpId, Instant.now())).isEqualTo(1);
        assertThat(repository.missing(erpId, Instant.now())).isZero();
        assertThat(repository.binding(productA).orElseThrow().state()).isEqualTo("PENDING_REVALIDATION");
        assertThat(repository.projections().stream().filter(p -> p.erpServiceId().equals(erpId)).findFirst().orElseThrow().available()).isFalse();
        repository.seen(erpId, Instant.now());
        assertThat(repository.binding(productA).orElseThrow().state()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT payload::text FROM erp_catalog_services WHERE erp_service_id = ?", String.class, erpId)).isEqualTo(payload);
    }
    @Test void failurePreservesLastSuccessfulSynchronization() {
        Instant success = Instant.parse("2026-09-29T10:00:00Z");
        repository.success(success, 1, 1, 0);
        repository.failure(success.plusSeconds(5), "ERP_ERROR");
        assertThat(repository.status().succeededAt()).isEqualTo(success);
        assertThat(repository.status().outcome()).isEqualTo("ERP_ERROR");
        assertThat(repository.revisions()).containsKey(erpId);
    }
}
