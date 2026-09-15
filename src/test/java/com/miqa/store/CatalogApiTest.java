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
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("miqa_store_test_db");
        jdbc.update("update products set featured = (id <> 'roll-up') where id in ('tarjetas-personales','volantes-a5','roll-up','vinil-impreso','banner')");
        jdbc.update("insert into categories(id,name,slug,active,display_order) values ('test-inactive','Inactive','test-inactive',false,99) on conflict(id) do nothing");
        jdbc.update("insert into products(id,category_id,name,slug,short_description,description,sale_type,unit_label,published) values ('test-hidden','imprenta-papeleria','Hidden','test-hidden','','','QUANTITY','unidad',false), ('test-inactive-product','test-inactive','Inactive category','test-inactive-product','','','QUANTITY','unidad',true) on conflict(id) do nothing");
        jdbc.update("insert into product_materials(id,product_id,name,active) values ('test-hidden-material','vinil-impreso','Hidden material',false) on conflict(id) do nothing");
        jdbc.update("insert into product_extras(id,product_id,name,active) values ('test-hidden-extra','vinil-impreso','Hidden extra',false) on conflict(id) do nothing");
    }
    @AfterEach void cleanup() {
        jdbc.update("delete from products where id in ('test-hidden','test-inactive-product')");
        jdbc.update("delete from categories where id = 'test-inactive'");
        jdbc.update("delete from product_materials where id = 'test-hidden-material'");
        jdbc.update("delete from product_extras where id = 'test-hidden-extra'");
        jdbc.update("update products set featured=true where id='roll-up'");
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
    @Test void categoriesAreActiveAndOrdered() throws Exception {
        var categories = json("/api/public/categories");
        assertThat(slugs(categories)).containsExactly("impresion-gran-formato","letreros-publicitarios","merchandising","imprenta-papeleria","senaletica","branding-instalaciones");
        assertThat(categories.valueStream().map(category -> category.get("catalogHeadline").asText())).containsExactly(
                "Imprime tus ideas.", "Haz visible tu marca.", "Personaliza lo que quieras.",
                "Tu marca también está en los detalles.", "Comunica, orienta y destaca.",
                "Soluciones integrales a la medida de tu marca.");
        assertThat(categories.valueStream().map(category -> category.get("catalogDescription").asText())).containsExactly(
                "Soluciones de impresión para interiores y exteriores.",
                "Letreros y soluciones para fachadas, negocios y espacios comerciales.",
                "Productos personalizados para tu marca, negocio o evento.",
                "Tarjetas, volantes, dípticos, calendarios y papelería corporativa.",
                "Señalización personalizada para empresas y espacios.",
                "Diseño, producción e instalación en un solo lugar.");
    }
    @Test void healthIsAggregateOnlyAndSensitiveActuatorEndpointsAreNotPublic() throws Exception {
        assertThat(json("/actuator/health").toString()).isEqualTo("{\"status\":\"UP\"}");
        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump")) {
            assertThat(request(path).statusCode()).isIn(401, 403, 404);
        }
    }
    @Test void productsArePublishedOrderedAndCompatible() throws Exception {
        var products = json("/api/public/products");
        assertThat(slugs(products)).containsExactly("tarjetas-personales","volantes-a5","roll-up","vinil-impreso","banner");
        var first = products.get(0);
        assertThat(first.get("id").isString()).isTrue();
        assertThat(first.get("saleType").asText()).isEqualTo("PACK");
        assertThat(first.get("packSize").asInt()).isEqualTo(1000);
        assertThat(first.get("step").asInt()).isEqualTo(1);
        assertThat(first.get("categorySlug").asText()).isEqualTo("imprenta-papeleria");
        assertThat(first.get("image").asText()).isEqualTo("/images/products/tarjetas-personales.png");
        assertThat(first.has("price")).isFalse();
        assertThat(first.get("gallery").isArray()).isTrue();
    }
    @Test void categoryFilter() throws Exception {
        assertThat(slugs(json("/api/public/products?category=imprenta-papeleria"))).containsExactly("tarjetas-personales","volantes-a5");
        assertThat(json("/api/public/products?category=unknown").size()).isZero();
    }
    @Test void searchIsCaseInsensitiveAndTreatsWildcardsLiterally() throws Exception {
        assertThat(slugs(json("/api/public/products?search=" + URLEncoder.encode(" TARJETAS ", StandardCharsets.UTF_8)))).containsExactly("tarjetas-personales");
        assertThat(json("/api/public/products?search=%25").size()).isZero();
        assertThat(json("/api/public/products?search=_").size()).isZero();
    }
    @Test void featuredFilterSupportsTrueAndFalse() throws Exception {
        assertThat(json("/api/public/products?featured=true").size()).isEqualTo(4);
        assertThat(slugs(json("/api/public/products?featured=false"))).containsExactly("roll-up");
    }
    @Test void filtersCombine() throws Exception {
        assertThat(slugs(json("/api/public/products?category=imprenta-papeleria&search=volantes&featured=true"))).containsExactly("volantes-a5");
        assertThat(json("/api/public/products?category=imprenta-papeleria&featured=false").size()).isZero();
    }
    @Test void detailIncludesOnlyActiveOptionsAndImageMetadata() throws Exception {
        var product = json("/api/public/products/vinil-impreso");
        assertThat(product.get("materials").valueStream().map(node -> node.get("id").asText()).toList()).containsExactly("blanco","transparente","microperforado");
        assertThat(product.get("extras").valueStream().map(node -> node.get("id").asText()).toList()).containsExactly("laminado","corte-especial");
        assertThat(product.get("images").get(0).get("primaryImage").asBoolean()).isTrue();
        assertThat(product.get("category").get("slug").asText()).isEqualTo("impresion-gran-formato");
    }
    @Test void missingUnpublishedAndInactiveCategoryProductsReturn404() throws Exception {
        for (String slug : List.of("missing","test-hidden","test-inactive-product")) {
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
    @Test void flywayAppliedMigrationsAndConstraintsAreEnforced() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> jdbc.update("update products set pack_size=null where id='tarjetas-personales'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from categories where id='imprenta-papeleria'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into product_images(id,product_id,url,alt_text,primary_image) values ('duplicate-primary','banner','/x.png','x',true)")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update categories set catalog_headline='<strong>Unsafe</strong>' where id='senaletica'")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
