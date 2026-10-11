package com.miqa.store.catalog;

import com.miqa.store.admin.AdminSecurity;
import com.miqa.store.admin.AdminUserRepository;
import com.miqa.store.config.CorsConfiguration;
import com.miqa.store.error.ApiExceptionHandler;
import com.miqa.store.error.CatalogNotFoundException;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC/security/SEO service, no Boot auto-configuration, JDBC or external services. */
class SeoPageHttpTest {
    private static final String ORIGIN = "https://store.solucionesmicaela.com";
    private static final String PATH = "/api/public/seo/pages/";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CatalogService catalog;

    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class, SeoPageController.class, SeoPageService.class, SitemapController.class,
            SitemapService.class, CatalogController.class, ApiExceptionHandler.class, CorsConfiguration.class})
    static class Config {
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean AdminUserRepository users() { return mock(AdminUserRepository.class); }
        @Bean CatalogService catalog() { return mock(CatalogService.class); }
        @Bean QuoteRequestRateLimit limit() { return new QuoteRequestRateLimit(120); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var props = new java.util.Properties();
            props.put("app.admin.jwt-secret", java.util.Base64.getEncoder().encodeToString(new byte[32]));
            props.put("miqa.cors.allowed-origins", "http://localhost:4200");
            config.setProperties(props); return config;
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        catalog = context.getBean(CatalogService.class);
        when(catalog.categories()).thenReturn(List.of(category("impresion", " Texto editorial. ")));
        when(catalog.categorySlugRedirects()).thenReturn(List.of());
        when(catalog.product(anyString())).thenThrow(new CatalogNotFoundException("private reason"));
        doReturn(product("banner", " Título SEO ", " Descripción SEO ", "Resumen", "Cuerpo")).when(catalog).product("banner");
    }
    @AfterEach void close() { context.close(); }

    @Test void anonymousEligibleProductHasExactPublicContractAndCanonicalOrigin() throws Exception {
        String json = mvc.perform(get(PATH + "banner").header("Host", "attacker.invalid")
                        .header("X-Forwarded-Host", "attacker.invalid").param("token", "private-query"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("PRODUCT"))
                .andExpect(jsonPath("$.name").value("Banner"))
                .andExpect(jsonPath("$.slug").value("banner"))
                .andExpect(jsonPath("$.seoTitle").value("Título SEO"))
                .andExpect(jsonPath("$.seoDescription").value("Descripción SEO"))
                .andExpect(jsonPath("$.bodyDescription").value("Cuerpo"))
                .andExpect(jsonPath("$.image.url").value(ORIGIN + "/images/primary.png"))
                .andExpect(jsonPath("$.image.altText").value("Principal"))
                .andExpect(jsonPath("$.canonicalUrl").value(ORIGIN + "/productos/banner"))
                .andExpect(jsonPath("$.breadcrumbs[0].url").value(ORIGIN + "/"))
                .andExpect(jsonPath("$.breadcrumbs[1].name").value("Productos"))
                .andExpect(jsonPath("$.breadcrumbs[2].url").value(ORIGIN + "/productos/banner"))
                .andExpect(jsonPath("$.links").isEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(JsonMapper.builder().build().readTree(json).size()).isEqualTo(10);
        assertThat(json).doesNotContain("erp-private", "internal-id", "revision-private", "configuration",
                "materials", "extras", "published", "featured", "packSize", "displayOrder", "attacker.invalid", "private-query");
        verifyNoInteractions(context.getBean(AdminUserRepository.class));
    }

    @Test void publicCategoryUsesEditorialFormulaAndOnlyPublicDeduplicatedProductLinks() throws Exception {
        var product = product("banner", null, null, "Resumen", "Cuerpo");
        when(catalog.products("impresion", null, null)).thenReturn(List.of(product, product));
        mvc.perform(get(PATH + "impresion")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("CATEGORY"))
                .andExpect(jsonPath("$.seoTitle").value("Impresión en Trujillo | MIQA"))
                .andExpect(jsonPath("$.seoDescription").value("Texto editorial. Conoce las opciones de impresión de MIQA en Trujillo."))
                .andExpect(jsonPath("$.bodyDescription").value("Texto editorial."))
                .andExpect(jsonPath("$.image.url").value(ORIGIN + "/images/brand/logo-miqa3.png"))
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].name").value("Banner"))
                .andExpect(jsonPath("$.links[0].url").value(ORIGIN + "/productos/banner"));
        verify(catalog, never()).product(anyString());
        verify(catalog).products("impresion", null, null);
    }

    @Test void publicCategoryWinsOverProductWithSameSlug() throws Exception {
        doReturn(product("impresion", "Producto", "Producto", "", "")).when(catalog).product("impresion");
        mvc.perform(get(PATH + "impresion")).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("CATEGORY"));
        verify(catalog, never()).product("impresion");
        verify(catalog, never()).categorySlugRedirects();
    }

    @Test void eligibleProductWinsOverAliasAndHiddenCategoryWithSameSlug() throws Exception {
        when(catalog.categorySlugRedirects()).thenReturn(List.of(new CatalogDtos.CategorySlugRedirectDto("banner", "impresion")));
        mvc.perform(get(PATH + "banner")).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("PRODUCT"));
        verify(catalog, never()).categorySlugRedirects();
    }

    @ParameterizedTest @ValueSource(strings = {"despublicado", "categoria-inactiva", "categoria-vacia", "inexistente", "ineligible"})
    void unavailableResourcesAreIndistinguishableAndNeverCache404(String slug) throws Exception {
        String json = mvc.perform(get(PATH + slug)).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Recurso no disponible"))
                .andExpect(jsonPath("$.errors").isEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("private reason", "erp", "stackTrace", "configuration");
    }

    @Test void publicCategoryAliasIs301WithCanonicalLocationAndEmptyBody() throws Exception {
        when(catalog.categorySlugRedirects()).thenReturn(List.of(new CatalogDtos.CategorySlugRedirectDto("categoria-anterior", "impresion")));
        mvc.perform(get(PATH + "categoria-anterior").param("search", "private"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", ORIGIN + "/productos/impresion"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
    }

    @Test void aliasWhoseDestinationIsNoLongerPublicIs404() throws Exception {
        when(catalog.categorySlugRedirects()).thenReturn(List.of(new CatalogDtos.CategorySlugRedirectDto("categoria-anterior", "categoria-oculta")));
        mvc.perform(get(PATH + "categoria-anterior")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void productSlugHistoryIsNotInvented() throws Exception {
        mvc.perform(get(PATH + "banner-anterior")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest @ValueSource(strings = {"short", "long", "generic"})
    void productFallbacksMatchAngularAndKeepBodyDescriptionSeparate(String variant) throws Exception {
        String shortText = variant.equals("short") ? " Resumen " : " ";
        String longText = variant.equals("generic") ? null : " Descripción completa ";
        when(catalog.product("banner")).thenReturn(product("banner", " ", null, shortText, longText));
        String description = switch (variant) {
            case "short" -> "Resumen";
            case "long" -> "Descripción completa";
            default -> "Explora los productos y soluciones gráficas disponibles de MIQA.";
        };
        mvc.perform(get(PATH + "banner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seoTitle").value("Banner | MIQA"))
                .andExpect(jsonPath("$.seoDescription").value(description))
                .andExpect(jsonPath("$.bodyDescription").value(variant.equals("generic") ? "" : "Descripción completa"));
    }

    @Test void categoryWithoutEditorialTextUsesExistingFallback() throws Exception {
        when(catalog.categories()).thenReturn(List.of(category("impresion", " ")));
        mvc.perform(get(PATH + "impresion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seoDescription").value("Conoce las opciones de Impresión de MIQA en Trujillo y solicita una cotización para tu proyecto."))
                .andExpect(jsonPath("$.bodyDescription").value("Descripción pública de categoría"));
    }

    @Test void publicationChangesAreResolvedAgainOnEveryRequest() throws Exception {
        mvc.perform(get(PATH + "banner")).andExpect(status().isOk());
        when(catalog.product("banner")).thenThrow(new CatalogNotFoundException("unpublished"));
        mvc.perform(get(PATH + "banner")).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest @ValueSource(strings = {"categories", "product", "links", "aliases"})
    void queryFailuresAre500WithoutPrivateDetailsOrRedirects(String operation) throws Exception {
        var failure = new DataAccessResourceFailureException("password=secret; private SQL");
        String slug = "banner";
        switch (operation) {
            case "categories" -> when(catalog.categories()).thenThrow(failure);
            case "product" -> when(catalog.product("banner")).thenThrow(failure);
            case "links" -> { slug = "impresion"; when(catalog.products("impresion", null, null)).thenThrow(failure); }
            case "aliases" -> { slug = "old"; when(catalog.categorySlugRedirects()).thenThrow(failure); }
        }
        String json = mvc.perform(get(PATH + slug).accept("application/json")).andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("password", "secret", "private SQL", "DataAccess");
    }

    @ParameterizedTest @ValueSource(strings = {"INVALID", "bad_slug", "bad..slug"})
    void invalidSlugIsUnavailableWithoutCatalogQueries(String slug) throws Exception {
        mvc.perform(get(PATH + slug)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(catalog);
    }

    @Test void overlongSlugIsUnavailableWithoutCatalogQueries() throws Exception {
        mvc.perform(get(PATH + "a".repeat(161))).andExpect(status().isNotFound());
        verifyNoInteractions(catalog);
    }

    @Test void headWorksButPostIsRejectedAndAdminStillRequiresAuthentication() throws Exception {
        mvc.perform(head(PATH + "banner")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
        mvc.perform(head(PATH + "missing")).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
        mvc.perform(post(PATH + "banner")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
    }

    @Test void coexistsWithSitemapAndExistingProductContract() throws Exception {
        when(catalog.products(null, null, null)).thenReturn(List.of(product("banner", "SEO", "SEO desc", "", "")));
        mvc.perform(get(SitemapController.PATH).accept("application/xml"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/xml;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/public/products/banner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seoTitle").value(" Título SEO "))
                .andExpect(jsonPath("$.configuration.erpServiceId").value("erp-private-service"));
    }

    private static CatalogDtos.CategoryDto category(String slug, String editorial) {
        return new CatalogDtos.CategoryDto("internal-id-category", "Impresión", slug,
                "Descripción pública de categoría", "Headline", editorial, 2, "erp-private-category");
    }
    private static CatalogDtos.ProductDto product(String slug, String title, String seoDescription, String shortText, String body) {
        return new CatalogDtos.ProductDto("internal-id-product", slug, "Banner", shortText, body, "impresion",
                category("impresion", "Editorial"), "/images/secondary.png", List.of(), List.of(
                        new CatalogDtos.ImageDto("internal-id-secondary", "/images/secondary.png", "Secundaria", false, 0),
                        new CatalogDtos.ImageDto("internal-id-primary", "/images/primary.png", "Principal", true, 5)),
                true, true, null, "unidad", null, null, 1, 1,
                List.of(new CatalogDtos.OptionDto("erp-private-material", "Material")), List.of(),
                new com.miqa.store.erp.PublicErpConfiguration.Configuration("ERP", "erp-private-service", "revision-private", "1", null),
                title, seoDescription);
    }
}
