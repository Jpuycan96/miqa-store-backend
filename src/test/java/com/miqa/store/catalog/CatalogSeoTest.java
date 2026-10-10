package com.miqa.store.catalog;

import com.miqa.store.config.MediaProperties;
import com.miqa.store.erp.PublicErpConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogSeoTest {
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PublicErpConfiguration configurations = mock(PublicErpConfiguration.class);
    private final CatalogService catalog = new CatalogService(categories, mock(CategorySlugAliasRepository.class),
            products, new MediaProperties(), configurations);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Impresión & diseño <MIQA> — Perú"})
    void listAndDetailExposeStoredEditorialSeoWithoutReplacingMissingValues(String seo) {
        var category = new Category();
        category.setId("category"); category.setSlug("impresion"); category.setName("Impresión");
        var product = new Product();
        product.setId("product"); product.setSlug("banner"); product.setCategory(category);
        product.setName("Banner"); product.setDescription("Descripción editorial");
        product.setShortDescription("Resumen"); product.setPublished(true);
        String seoDescription = seo == null || seo.isEmpty() ? seo : "Descripción: " + seo;
        product.setSeoTitle(seo); product.setSeoDescription(seoDescription);
        var configuration = new PublicErpConfiguration.Configuration("ERP", "service", "revision", "1", null);
        when(configurations.visibleProductIds()).thenReturn(Set.of("product"));
        when(configurations.configuration("product")).thenReturn(configuration);
        when(products.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(product));
        when(products.findBySlugAndPublishedTrueAndCategoryActiveTrue("banner")).thenReturn(Optional.of(product));

        var detail = catalog.product("banner");
        assertThat(catalog.products(null, null, null)).containsExactly(detail);
        var json = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(detail));
        assertThat(json.has("seoTitle")).isTrue();
        assertThat(json.has("seoDescription")).isTrue();
        assertThat(detail.seoTitle()).isEqualTo(seo);
        assertThat(detail.seoDescription()).isEqualTo(seoDescription);
        if (seo == null) {
            assertThat(json.get("seoTitle").isNull()).isTrue();
            assertThat(json.get("seoDescription").isNull()).isTrue();
        } else {
            assertThat(json.get("seoTitle").asText()).isEqualTo(seo);
            assertThat(json.get("seoDescription").asText()).isEqualTo(seoDescription);
        }
        assertThat(detail.description()).isEqualTo("Descripción editorial");
        assertThat(detail.configuration()).isSameAs(configuration);
        assertThat(product.getSeoTitle()).isEqualTo(seo);
        assertThat(product.isPublished()).isTrue();
    }

    @Test void sitemapUsesPublicEligibilityAndDoesNotExportHiddenOrEmptyCategories() {
        var visibleCategory = mock(Category.class);
        when(visibleCategory.getId()).thenReturn("visible");
        when(visibleCategory.getSlug()).thenReturn("impresion");
        when(visibleCategory.getErpCategoryId()).thenReturn("erp-category");
        var emptyCategory = mock(Category.class);
        when(emptyCategory.getId()).thenReturn("empty");
        when(emptyCategory.getErpCategoryId()).thenReturn("erp-empty");
        var legacyCategory = mock(Category.class);
        when(legacyCategory.getId()).thenReturn("legacy");
        var visibleProduct = new Product();
        visibleProduct.setId("visible-product"); visibleProduct.setCategory(visibleCategory);
        when(configurations.visibleProductIds()).thenReturn(Set.of("visible-product"));
        when(products.findAllById(Set.of("visible-product"))).thenReturn(List.of(visibleProduct));
        when(categories.findByActiveTrueOrderByDisplayOrderAscIdAsc())
                .thenReturn(List.of(visibleCategory, emptyCategory, legacyCategory));
        when(products.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        assertThat(new SitemapService(catalog).xml()).contains("/productos/impresion")
                .doesNotContain("erp-category", "/productos/empty", "/productos/legacy");

        when(configurations.visibleProductIds()).thenReturn(Set.of());
        when(products.findAllById(Set.of())).thenReturn(List.of());
        clearInvocations(products);
        assertThat(new SitemapService(catalog).xml()).doesNotContain("/productos/impresion");
        verify(products, never()).findAll(any(Specification.class), any(Sort.class));
    }
}
