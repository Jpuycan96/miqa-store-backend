package com.miqa.store;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogApiTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void fixtures() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("miqa_store_test_db");
        cleanup();
        jdbc.update("INSERT INTO categories(id,name,slug,erp_category_id) VALUES ('catalog-test-category','ERP category','catalog-test-category','970000001')");
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES ('catalog-test-legacy-category','Legacy','catalog-test-legacy-category')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,short_description,description,sale_type,unit_label,published) VALUES ('catalog-test-legacy','catalog-test-legacy-category','Legacy','catalog-test-legacy','','','QUANTITY','unidad',true)");
        for (int i=1; i<=3; i++) {
            String id="catalog-test-product-"+i, erpId="97000001"+i;
            var contract=new com.miqa.store.erp.ErpCatalogContract(1,"ERP_GIGANTOGRAFIAS",erpId,"Technical service",
                new com.miqa.store.erp.ErpCatalogContract.Category("970000001","ERP category"),true,true,"CONFIGURADA",List.of(),"1","a".repeat(64),java.time.Instant.now(),
                new com.miqa.store.erp.ErpCatalogContract.Configuration(
                    new com.miqa.store.erp.ErpCatalogContract.Quantity("unidad","1","1",null,false,0,"1000"),"FIJO","ESCALA",
                    new com.miqa.store.erp.ErpCatalogContract.Measurements("NINGUNA",null,List.of("cantidad")),
                    List.of(new com.miqa.store.erp.ErpCatalogContract.Material("1","ERP material","SIN_MODELO",List.of()))));
            jdbc.update("INSERT INTO erp_catalog_services(erp_service_id,catalog_revision,payload,sync_state,last_synced_at) VALUES (?, ?, CAST(? AS jsonb),'AVAILABLE',current_timestamp)",erpId,contract.catalogRevision(),mapper.writeValueAsString(contract));
            jdbc.update("INSERT INTO products(id,category_id,name,slug,short_description,description,catalog_mode,published,featured,display_order) VALUES (?,'catalog-test-category',?,?,'Editorial','Editorial','ERP',?,?,?)",id,"Editorial "+i,id,i<3,i==1,i);
            jdbc.update("INSERT INTO product_erp_bindings(product_id,erp_service_id,canonical) VALUES (?,?,true)",id,erpId);
        }
        jdbc.update("INSERT INTO product_images(id,product_id,url,alt_text,primary_image) VALUES ('catalog-test-image','catalog-test-product-1','/synthetic.png','Synthetic',true)");
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM product_erp_bindings WHERE product_id LIKE 'catalog-test-%'");
        jdbc.update("DELETE FROM products WHERE id LIKE 'catalog-test-%'");
        jdbc.update("DELETE FROM categories WHERE id LIKE 'catalog-test-%'");
        jdbc.update("DELETE FROM erp_catalog_services WHERE erp_service_id IN ('970000011','970000012','970000013')");
    }
    private HttpResponse<String> request(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(String path) throws Exception {
        var response = request(path);
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
    private List<String> slugs(JsonNode array) {
        return array.valueStream().map(node -> node.get("slug").asText()).toList();
    }
    @Test void categoriesComeOnlyFromVisibleErpServices() throws Exception {
        var categories=json("/api/public/categories");
        assertThat(slugs(categories)).containsExactly("catalog-test-category");
        assertThat(categories.get(0).get("erpCategoryId").asText()).isEqualTo("970000001");
        jdbc.update("UPDATE erp_catalog_services SET sync_state='PENDING_REVALIDATION' WHERE erp_service_id IN ('970000011','970000012')");
        assertThat(json("/api/public/categories").size()).isZero();
        assertThat(json("/api/public/products").size()).isZero();
    }
    @Test void healthIsAggregateOnlyAndSensitiveActuatorEndpointsAreNotPublic() throws Exception {
        assertThat(json("/actuator/health").toString()).isEqualTo("{\"status\":\"UP\"}");
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump")) {
            assertThat(request(path).statusCode()).isIn(401, 403, 404);
        }
    }
    @Test void productsAreCanonicalErpPublicationsOnly() throws Exception {
        var products=json("/api/public/products");
        assertThat(slugs(products)).containsExactly("catalog-test-product-1","catalog-test-product-2");
        assertThat(products.get(0).get("configuration").get("mode").asText()).isEqualTo("ERP");
        assertThat(products.get(0).get("saleType").isNull()).isTrue();
        assertThat(request("/api/public/products/catalog-test-legacy").statusCode()).isEqualTo(404);
        jdbc.update("UPDATE product_erp_bindings SET canonical=false WHERE product_id='catalog-test-product-1'");
        assertThat(request("/api/public/products/catalog-test-product-1").statusCode()).isEqualTo(404);
    }
    @Test void categoryFilterUsesProjectedCategorySlug() throws Exception {
        assertThat(slugs(json("/api/public/products?category=catalog-test-category"))).hasSize(2);
        assertThat(json("/api/public/products?category=catalog-test-legacy-category").size()).isZero();
    }
    @Test void searchIsCaseInsensitiveAndTreatsWildcardsLiterally() throws Exception {
        assertThat(slugs(json("/api/public/products?search=EDITORIAL"))).hasSize(2);
        assertThat(json("/api/public/products?search=%25").size()).isZero();
        assertThat(json("/api/public/products?search=_").size()).isZero();
    }
    @Test void featuredFilterSupportsTrueAndFalse() throws Exception {
        assertThat(slugs(json("/api/public/products?featured=true"))).containsExactly("catalog-test-product-1");
        assertThat(slugs(json("/api/public/products?featured=false"))).containsExactly("catalog-test-product-2");
    }
    @Test void filtersCombine() throws Exception {
        assertThat(slugs(json("/api/public/products?category=catalog-test-category&search=Editorial&featured=true"))).containsExactly("catalog-test-product-1");
    }
    @Test void detailUsesErpConfigurationAndEditorialImagesWithoutLegacyOptions() throws Exception {
        var product=json("/api/public/products/catalog-test-product-1");
        assertThat(product.get("materials").size()).isZero();
        assertThat(product.get("extras").size()).isZero();
        assertThat(product.get("images").get(0).get("primaryImage").asBoolean()).isTrue();
        assertThat(product.get("configuration").get("configuration").get("materiales").get(0).get("erpMaterialId").asText()).isEqualTo("1");
        jdbc.update("UPDATE product_erp_bindings SET active=false WHERE product_id='catalog-test-product-1'");
        assertThat(request("/api/public/products/catalog-test-product-1").statusCode()).isEqualTo(404);
    }
    @Test void missingUnpublishedAndInactiveCategoryProductsReturn404() throws Exception {
        for (String slug : List.of("missing","catalog-test-product-3","catalog-test-legacy")) {
            var response = request("/api/public/products/" + slug);
            assertThat(response.statusCode()).isEqualTo(404);
            var error = mapper.readTree(response.body());
            assertThat(error.get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(error.has("timestamp")).isTrue();
            assertThat(error.has("trace")).isFalse();
        }
    }
    @Test void invalidFiltersHaveConsistent400Responses() throws Exception {
        for (String query : List.of("featured=maybe", "category=INVALID", "search=" + "a".repeat(121))) {
            var response = request("/api/public/products?" + query);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
    }
    @Test void unknownRouteAndWriteMethodsAreRejected() throws Exception {
        assertThat(request("/api/public/unknown").statusCode()).isEqualTo(404);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/public/products")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }
    @Test void corsAllowsOnlyLocalFrontend() throws Exception {
        for (String origin : List.of("http://localhost:4200", "https://not-allowed.example")) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/public/products")).header("Origin",origin).header("Access-Control-Request-Method","GET").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (origin.startsWith("http://localhost")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
            } else {
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }
    @Test void flywayAppliedMigrationsAndAuthorityConstraintsAreEnforced() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(12);
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET sale_type='AREA' WHERE id='catalog-test-product-1'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE product_erp_bindings SET erp_service_id='970000011' WHERE product_id='catalog-test-product-2'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM categories WHERE id='catalog-test-category'")).isInstanceOf(DataIntegrityViolationException.class);
    }

}
