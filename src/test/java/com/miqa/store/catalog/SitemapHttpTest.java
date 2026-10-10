package com.miqa.store.catalog;

import com.miqa.store.admin.AdminSecurity;
import com.miqa.store.admin.AdminUserRepository;
import com.miqa.store.config.CorsConfiguration;
import com.miqa.store.error.ApiExceptionHandler;
import com.miqa.store.quote.QuoteRequestRateLimit;
import jakarta.servlet.Filter;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.xml.sax.InputSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SitemapHttpTest {
    private static final String ORIGIN = "https://store.solucionesmicaela.com";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private CatalogService catalog;

    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import({AdminSecurity.class, SitemapController.class, SitemapService.class, CatalogController.class,
            ApiExceptionHandler.class, CorsConfiguration.class})
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
        when(catalog.categories()).thenReturn(List.of(category("impresion")));
        when(catalog.products(null, null, null)).thenReturn(List.of(product("banner")));
    }
    @AfterEach void close() { context.close(); }

    @Test void anonymousXmlUsesCanonicalOriginPublicRoutesAndNoPrivateDataOrInventedDates() throws Exception {
        String xml = response();
        assertThat(locations(xml)).containsExactly(ORIGIN + "/", ORIGIN + "/productos",
                ORIGIN + "/productos/impresion", ORIGIN + "/productos/banner");
        assertThat(xml).doesNotContain("lastmod", "erp-private-id", "Editorial SEO", "attacker.invalid");
        verify(catalog).categories();
        verify(catalog).products(null, null, null);
    }

    @Test void escapesXmlEncodesPathSegmentsAndDeduplicatesAcrossProductsAndCategories() throws Exception {
        when(catalog.categories()).thenReturn(List.of(category("same"), category("same"), category("a&b")));
        when(catalog.products(null, null, null)).thenReturn(List.of(product("same"), product("a&b"), product("á/<tag>?")));
        String xml = response();
        assertThat(xml).contains("a&amp;b").doesNotContain("<tag>");
        assertThat(locations(xml)).containsExactly(ORIGIN + "/", ORIGIN + "/productos",
                ORIGIN + "/productos/same", ORIGIN + "/productos/a&b",
                ORIGIN + "/productos/%C3%A1%2F%3Ctag%3E%3F");
    }

    @Test void nextRequestReflectsPublicationAndSlugChangesWithoutABuild() throws Exception {
        assertThat(locations(response())).contains(ORIGIN + "/productos/banner");
        when(catalog.categories()).thenReturn(List.of(category("nueva-categoria")));
        when(catalog.products(null, null, null)).thenReturn(List.of(product("nuevo-slug")));
        assertThat(locations(response())).containsExactly(ORIGIN + "/", ORIGIN + "/productos",
                ORIGIN + "/productos/nueva-categoria", ORIGIN + "/productos/nuevo-slug");
        when(catalog.categories()).thenReturn(List.of());
        when(catalog.products(null, null, null)).thenReturn(List.of());
        assertThat(locations(response())).containsExactly(ORIGIN + "/", ORIGIN + "/productos");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void queryFailuresReturn500EvenWhenClientOnlyAcceptsXml(boolean categoryFailure) throws Exception {
        var failure = new DataAccessResourceFailureException("private SQL / credentials must not escape");
        if (categoryFailure) when(catalog.categories()).thenThrow(failure);
        else when(catalog.products(null, null, null)).thenThrow(failure);
        mvc.perform(get(SitemapController.PATH).accept("application/xml"))
                .andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
    }

    @Test void invalidPublicSlugFailsWithoutPublishingAPartialSitemap() throws Exception {
        when(catalog.products(null, null, null)).thenReturn(List.of(product(null)));
        mvc.perform(get(SitemapController.PATH).accept("application/xml"))
                .andExpect(status().isInternalServerError()).andExpect(content().string(""));
    }

    @Test void productListAndDetailJsonIncludeEditorialSeo() throws Exception {
        when(catalog.product("banner")).thenReturn(product("banner"));
        mvc.perform(get("/api/public/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seoTitle").value("Editorial SEO"))
                .andExpect(jsonPath("$[0].seoDescription").value("Descripción editorial SEO"));
        mvc.perform(get("/api/public/products/banner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seoTitle").value("Editorial SEO"))
                .andExpect(jsonPath("$.seoDescription").value("Descripción editorial SEO"));
    }

    private String response() throws Exception {
        return mvc.perform(get(SitemapController.PATH).accept("application/xml").header("Host", "attacker.invalid"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/xml;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }
    private List<String> locations(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("urlset");
        var nodes = document.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        var result = new java.util.ArrayList<String>();
        for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent());
        return result;
    }
    private static CatalogDtos.CategoryDto category(String slug) {
        return new CatalogDtos.CategoryDto("category-id", "Categoría", slug, "Descripción", null, null, 0, "erp-private-id");
    }
    private static CatalogDtos.ProductDto product(String slug) {
        return new CatalogDtos.ProductDto("product-id", slug, "Banner", "Resumen", "Descripción", "impresion",
                category("impresion"), "", List.of(), List.of(), false, true, null, null,
                null, null, null, null, List.of(), List.of(), null, "Editorial SEO", "Descripción editorial SEO");
    }
}
