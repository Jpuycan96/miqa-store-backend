package com.miqa.store.quote;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL integration suite. Requires separate authorization to apply V7 to miqa_store_test_db. */
@Tag("postgresql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.quote-requests.max-requests-per-minute=10000")
@ActiveProfiles("test")
class QuoteRequestApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void fixtures() {
        // LocalDatabaseGuard additionally rejects every non-test URL BEFORE DataSource/Flyway startup.
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("miqa_store_test_db");
        cleanup();
        jdbc.update("""
                INSERT INTO categories(id, name, slug, active) VALUES
                ('qrt-active', 'Categoría histórica', 'qrt-active', true), ('qrt-inactive', 'Oculta', 'qrt-inactive', false)
                """);
        for (String id : List.of("qrt-quantity", "qrt-pack", "qrt-area", "qrt-other", "qrt-hidden", "qrt-inactive-product")) {
            String type = id.equals("qrt-pack") ? "PACK" : id.equals("qrt-area") ? "AREA" : "QUANTITY";
            jdbc.update("""
                    INSERT INTO products(id, category_id, name, slug, short_description, description, sale_type, unit_label,
                        pack_size, pack_label, min_quantity, quantity_step, published)
                    VALUES (?, ?, ?, ?, '', '', ?, ?, ?, ?, 12, 12, ?)
                    """, id, id.equals("qrt-inactive-product") ? "qrt-inactive" : "qrt-active", "Oficial " + id, id, type,
                    type.equals("AREA") ? "m²" : "unidad", type.equals("PACK") ? 1000 : null,
                    type.equals("PACK") ? "millar" : null, !id.equals("qrt-hidden"));
        }
        jdbc.update("""
                INSERT INTO product_materials(id, product_id, name, active) VALUES
                ('qrt-material', 'qrt-area', 'Material histórico', true),
                ('qrt-foreign-material', 'qrt-other', 'Material ajeno', true),
                ('qrt-inactive-material', 'qrt-area', 'Material inactivo', false)
                """);
        jdbc.update("""
                INSERT INTO product_extras(id, product_id, name, active) VALUES
                ('qrt-extra', 'qrt-area', 'Extra histórico', true),
                ('qrt-foreign-extra', 'qrt-other', 'Extra ajeno', true),
                ('qrt-inactive-extra', 'qrt-area', 'Extra inactivo', false)
                """);
    }
    @AfterEach void cleanup() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("miqa_store_test_db");
        jdbc.update("DELETE FROM quote_request_items WHERE request_id IN (SELECT id FROM quote_requests WHERE contact_name = 'quote-test')");
        jdbc.update("DELETE FROM quote_requests WHERE contact_name = 'quote-test'");
        jdbc.update("DELETE FROM products WHERE id IN ('qrt-quantity','qrt-pack','qrt-area','qrt-other','qrt-hidden','qrt-inactive-product')");
        jdbc.update("DELETE FROM categories WHERE id IN ('qrt-active','qrt-inactive')");
    }

    @Test void anonymousSubmissionPersistsStateOriginAllTypesAndVersionedOfficialSnapshot() throws Exception {
        var area = area();
        area.put("materialId", "qrt-material");
        area.put("extraIds", List.of("qrt-extra"));
        area.put("productName", "forged");
        area.put("areaSquareMeters", 999);
        area.put("price", 0);
        var payload = body(item("qrt-quantity", "QUANTITY"), item("qrt-pack", "PACK"), area);
        payload.put("status", "APPROVED");
        payload.put("origin", "ERP");
        payload.put("reference", "forged");
        var response = post(key(), payload);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode confirmation = mapper.readTree(response.body());
        assertThat(confirmation.get("reference").asText()).matches("MIQA-[0-9]{6,}");
        assertThat(confirmation.propertyNames()).containsExactlyInAnyOrder("reference", "receivedAt", "confirmation");
        assertThat(response.body()).doesNotContain("quote-test", "999999999", "example.test", "snapshot", "requestHash", "idempotency");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var header = jdbc.queryForMap("SELECT * FROM quote_requests WHERE reference = ?", confirmation.get("reference").asText());
        assertThat(header.get("status")).isEqualTo("RECIBIDA");
        assertThat(header.get("origin")).isEqualTo("TIENDA_VIRTUAL");
        assertThat(header.get("id")).isNotEqualTo(header.get("reference"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quote_request_items WHERE request_id = ?", Integer.class, header.get("id"))).isEqualTo(3);
        var snapshots = jdbc.query("SELECT snapshot::text FROM quote_request_items WHERE request_id = ? ORDER BY position",
                (rs, index) -> mapper.readTree(rs.getString(1)), header.get("id"));
        assertThat(snapshots.get(0).get("quantity").asLong()).isEqualTo(50L);
        assertThat(snapshots.get(0).get("rules").get("quantityStep").asInt()).isEqualTo(12);
        assertThat(snapshots.get(1).get("packSize").asInt()).isEqualTo(1000);
        assertThat(snapshots.get(1).get("packLabel").asText()).isEqualTo("millar");
        assertThat(snapshots.get(2).get("areaSquareMeters").decimalValue()).isEqualByComparingTo("3.000000");
        assertThat(snapshots.get(2).get("material").get("name").asText()).isEqualTo("Material histórico");
        assertThat(snapshots.get(2).get("extras").get(0).get("name").asText()).isEqualTo("Extra histórico");
        for (var snapshot : snapshots) {
            assertThat(snapshot.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(snapshot.get("category").get("name").asText()).isEqualTo("Categoría histórica");
            assertThat(snapshot.toString()).doesNotContain("forged", "\"price\"");
        }
    }

    @Test void missingAndInvalidIdempotencyKeysAre400AndNoStore() throws Exception {
        for (String key : Arrays.asList(null, "invalid", "1-1-1-1-1")) {
            var response = post(key, body(item("qrt-quantity", "QUANTITY")));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
        assertThat(requestCount()).isZero();
    }
    @Test void replayReturnsIdenticalConfirmationEvenAfterCatalogDeletion() throws Exception {
        var item = area(); item.put("materialId", "qrt-material"); item.put("extraIds", List.of("qrt-extra"));
        var payload = body(item);
        String key = key();
        var first = post(key, payload);
        assertThat(first.statusCode()).isEqualTo(201);
        String snapshot = jdbc.queryForObject("SELECT snapshot::text FROM quote_request_items WHERE product_id='qrt-area'", String.class);
        jdbc.update("UPDATE products SET name='Changed', published=false WHERE id='qrt-area'");
        jdbc.update("UPDATE categories SET name='Changed category', active=false WHERE id='qrt-active'");
        jdbc.update("DELETE FROM product_materials WHERE id='qrt-material'");
        jdbc.update("DELETE FROM products WHERE id='qrt-area'");
        var replay = post(key, payload);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(requestCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot::text FROM quote_request_items WHERE product_id='qrt-area'", String.class)).isEqualTo(snapshot);
        assertThat(post(key(), payload).statusCode()).isEqualTo(409);
    }
    @Test void reusingKeyWithDifferentContentIs409() throws Exception {
        String key = key(); var item = item("qrt-quantity", "QUANTITY");
        assertThat(post(key, body(item)).statusCode()).isEqualTo(201);
        item.put("quantity", 51);
        var conflict = post(key, body(item));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(requestCount()).isEqualTo(1);
    }
    @Test void missingUnpublishedAndInactiveCategoryProductsConflict() throws Exception {
        for (String id : List.of("qrt-missing", "qrt-hidden", "qrt-inactive-product")) {
            assertThat(post(key(), body(item(id, "QUANTITY"))).statusCode()).isEqualTo(409);
        }
        assertThat(requestCount()).isZero();
    }
    @Test void foreignInactiveAndMissingMaterialsAndExtrasConflict() throws Exception {
        for (String material : List.of("qrt-foreign-material", "qrt-inactive-material", "qrt-missing-material")) {
            var item = area(); item.put("materialId", material);
            assertThat(post(key(), body(item)).statusCode()).isEqualTo(409);
        }
        for (String extra : List.of("qrt-foreign-extra", "qrt-inactive-extra", "qrt-missing-extra")) {
            var item = area(); item.put("extraIds", List.of(extra));
            assertThat(post(key(), body(item)).statusCode()).isEqualTo(409);
        }
        assertThat(requestCount()).isZero();
    }
    @Test void productWithActiveMaterialsRequiresAnExplicitSelection() throws Exception {
        var item = area(); item.remove("materialId");
        assertThat(post(key(), body(item)).statusCode()).isEqualTo(400);
        assertThat(requestCount()).isZero();
    }
    @Test void invalidDimensionsQuantityAndStalePresentationAreRejected() throws Exception {
        for (Object width : Arrays.asList(null, 0, -1, 0.009, 1001)) {
            var item = area(); item.put("widthMeters", width);
            assertThat(post(key(), body(item)).statusCode()).isEqualTo(400);
        }
        for (Object quantity : List.of(0, 11, 12.5, "50", 1_000_000_001L)) {
            var item = item("qrt-quantity", "QUANTITY"); item.put("quantity", quantity);
            assertThat(post(key(), body(item)).statusCode()).isEqualTo(400);
        }
        var pack = item("qrt-pack", "PACK"); pack.put("packSize", 500);
        assertThat(post(key(), body(pack)).statusCode()).isEqualTo(409);
        assertThat(post(key(), body(item("qrt-pack", "QUANTITY"))).statusCode()).isEqualTo(409);
        assertThat(requestCount()).isZero();
    }
    @Test void maximumItemsAndTextLengthsAreEnforced() throws Exception {
        var payload = body(item("qrt-quantity", "QUANTITY"));
        payload.put("items", Collections.nCopies(51, item("qrt-quantity", "QUANTITY")));
        assertThat(post(key(), payload).statusCode()).isEqualTo(400);
        payload = body(item("qrt-quantity", "QUANTITY")); payload.put("notes", "x".repeat(1001));
        assertThat(post(key(), payload).statusCode()).isEqualTo(400);
        payload = body(item("qrt-quantity", "QUANTITY")); payload.put("contact", Map.of("name", "x".repeat(161), "phone", "999999999"));
        assertThat(post(key(), payload).statusCode()).isEqualTo(400);
        assertThat(requestCount()).isZero();
    }
    @Test void oversizedBodyHas413NoStoreAndCorrectMessage() throws Exception {
        var response = send("POST", QuoteRequestBodyFilter.PATH, key(), " ".repeat(65537));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("64 KiB").doesNotContain("5 MB");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void concurrentSameKeyCreatesOneRequestAndReplaysOthers() throws Exception {
        String key = key();
        var responses = concurrent(false, key);
        assertThat(responses.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 200).count()).isEqualTo(7);
        assertThat(responses.stream().map(HttpResponse::body).distinct().count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }
    @Test void concurrentDifferentKeysReceiveUniqueReferences() throws Exception {
        var responses = concurrent(true, null);
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(responses.stream().map(response -> mapper.readTree(response.body()).get("reference").asText()).distinct().count()).isEqualTo(8);
        assertThat(requestCount()).isEqualTo(8);
    }
    @Test void corsPreflightAndAdminProtectionRemainCorrect() throws Exception {
        for (String origin : List.of("http://localhost:4200", "https://untrusted.example")) {
            var request = HttpRequest.newBuilder(uri(QuoteRequestBodyFilter.PATH)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,idempotency-key").build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.startsWith("http://localhost") ? 200 : 403);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            if (response.statusCode() == 200) assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")).containsIgnoringCase("idempotency-key");
        }
        assertThat(send("GET", "/api/admin/products", null, null).statusCode()).isIn(401, 403);
        assertThat(send("GET", QuoteRequestBodyFilter.PATH, null, null).statusCode()).isEqualTo(405);
        assertThat(send("GET", QuoteRequestBodyFilter.PATH + "/MIQA-000001", null, null).statusCode()).isEqualTo(404);
    }
    @Test void failedMultiItemRequestLeavesNoPartialHeaderOrItems() throws Exception {
        assertThat(post(key(), body(item("qrt-quantity", "QUANTITY"), item("qrt-missing", "QUANTITY"))).statusCode()).isEqualTo(409);
        assertThat(requestCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quote_request_items WHERE product_id='qrt-quantity'", Integer.class)).isZero();
    }

    private List<HttpResponse<String>> concurrent(boolean uniqueKeys, String sharedKey) throws Exception {
        var ready = new CountDownLatch(8); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<HttpResponse<String>>>();
            for (int index = 0; index < 8; index++) tasks.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency test start timeout");
                return post(uniqueKeys ? key() : sharedKey, body(item("qrt-quantity", "QUANTITY")));
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var result = new ArrayList<HttpResponse<String>>();
            for (var task : tasks) result.add(task.get(20, TimeUnit.SECONDS));
            return result;
        }
    }
    private Map<String, Object> item(String product, String type) {
        var item = new LinkedHashMap<String, Object>();
        item.put("productId", product); item.put("saleType", type); item.put("quantity", 50);
        if (type.equals("PACK")) item.put("packSize", 1000);
        return item;
    }
    private Map<String, Object> area() {
        var item = item("qrt-area", "AREA"); item.put("widthMeters", 2.5); item.put("heightMeters", 1.2);
        item.put("materialId", "qrt-material"); return item;
    }
    @SafeVarargs private final Map<String, Object> body(Map<String, Object>... items) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contact", Map.of("name", "quote-test", "phone", "+51999999999", "email", "quote-test@example.test"));
        payload.put("items", List.of(items)); return payload;
    }
    private String key() { return UUID.randomUUID().toString(); }
    private int requestCount() { return jdbc.queryForObject("SELECT count(*) FROM quote_requests WHERE contact_name='quote-test'", Integer.class); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> post(String key, Map<String, Object> body) throws Exception {
        return send("POST", QuoteRequestBodyFilter.PATH, key, mapper.writeValueAsString(body));
    }
    private HttpResponse<String> send(String method, String path, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
