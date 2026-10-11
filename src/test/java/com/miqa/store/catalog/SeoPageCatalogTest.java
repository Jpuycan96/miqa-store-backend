package com.miqa.store.catalog;

import com.miqa.store.config.MediaProperties;
import com.miqa.store.erp.PublicErpConfiguration;
import com.miqa.store.error.CatalogNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real CatalogService and resolver with repository/projection mocks; no database. */
class SeoPageCatalogTest {
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CategorySlugAliasRepository aliases = mock(CategorySlugAliasRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final PublicErpConfiguration configurations = mock(PublicErpConfiguration.class);
    private final MediaProperties media = new MediaProperties();
    private final CatalogService catalog = new CatalogService(categories, aliases, products, media, configurations);
    private final SeoPageService pages = new SeoPageService(catalog);
    private Category category;
    private Product product;

    @BeforeEach void setup() {
        category = spy(new Category());
        category.setId("category-private-id"); category.setName("Impresión"); category.setSlug("impresion");
        category.setActive(true); doReturn("erp-private-category").when(category).getErpCategoryId();
        category.setCatalogDescription("Editorial de impresión");
        product = new Product();
        product.setId("product-private-id"); product.setCategory(category); product.setPublished(true);
        product.setSlug("banner"); product.setName("Banner"); product.setShortDescription("Resumen");
        product.setDescription("Descripción pública");
        when(configurations.visibleProductIds()).thenReturn(Set.of(product.getId()));
        when(configurations.configuration(product.getId())).thenReturn(
                new PublicErpConfiguration.Configuration("ERP", "erp-private-service", "private-revision", "1", null));
        when(categories.findByActiveTrueOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(category));
        when(products.findAllById(Set.of(product.getId()))).thenReturn(List.of(product));
        when(products.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(product));
        when(products.findBySlugAndPublishedTrueAndCategoryActiveTrue(anyString())).thenReturn(Optional.empty());
        when(products.findBySlugAndPublishedTrueAndCategoryActiveTrue("banner")).thenReturn(Optional.of(product));
        when(aliases.findPublicRedirects()).thenReturn(List.of(new CategorySlugAlias("old-category", category)));
    }

    @Test void eligiblePublishedProductReusesPublicDetailAndDoesNotSerializeTechnicalConfiguration() {
        var page = ((SeoPageService.Found) pages.resolve("banner")).page();
        assertThat(page.type()).isEqualTo(SeoPageDtos.PageType.PRODUCT);
        assertThat(page.seoTitle()).isEqualTo("Banner | MIQA");
        assertThat(page.seoDescription()).isEqualTo("Resumen");
        assertThat(page.bodyDescription()).isEqualTo("Descripción pública");
        assertThat(page.image().url()).endsWith("/images/brand/logo-miqa3.png");
        assertThat(JsonMapper.builder().build().writeValueAsString(page))
                .doesNotContain("private-id", "erp-private", "private-revision", "configuration", "published", "catalogMode");
        verify(products).findBySlugAndPublishedTrueAndCategoryActiveTrue("banner");
        verify(configurations).configuration(product.getId());
        verify(products, never()).save(any()); verify(categories, never()).save(any()); verify(aliases, never()).save(any());
    }

    @Test void categoryWithEligibleProductsUsesExistingPublicList() {
        var page = ((SeoPageService.Found) pages.resolve("impresion")).page();
        assertThat(page.type()).isEqualTo(SeoPageDtos.PageType.CATEGORY);
        assertThat(page.links()).containsExactly(new SeoPageDtos.Link("Banner", "https://store.solucionesmicaela.com/productos/banner"));
        verify(products).findAll(any(Specification.class), eq(Sort.by("displayOrder").ascending().and(Sort.by("id"))));
        verify(configurations).configuration(product.getId());
        verify(products, never()).findBySlugAndPublishedTrueAndCategoryActiveTrue("impresion");
    }

    @ParameterizedTest @ValueSource(strings = {"unpublished", "inactive-category", "missing"})
    void publicRepositoryAbsenceRemains404WithoutCallingConfiguration(String reason) {
        if (reason.equals("unpublished")) product.setPublished(false);
        if (reason.equals("inactive-category")) category.setActive(false);
        // The same repository method used by CatalogService enforces publication/category activity.
        when(products.findBySlugAndPublishedTrueAndCategoryActiveTrue("banner")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> pages.resolve("banner")).isInstanceOf(CatalogNotFoundException.class);
        verify(configurations, never()).configuration(anyString());
    }

    @Test void technicallyIneligiblePublishedProductRemains404FromExistingProjectionRules() {
        when(configurations.configuration(product.getId())).thenThrow(new CatalogNotFoundException("technical private reason"));
        assertThatThrownBy(() -> pages.resolve("banner")).isInstanceOf(CatalogNotFoundException.class)
                .hasMessage("Recurso no disponible");
        verify(configurations).configuration(product.getId());
    }

    @ParameterizedTest @ValueSource(strings = {"inactive", "empty", "without-erp-identity"})
    void hiddenCategoryAndItsHistoricalAliasAre404(String reason) {
        switch (reason) {
            case "inactive" -> when(categories.findByActiveTrueOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
            case "empty" -> {
                when(configurations.visibleProductIds()).thenReturn(Set.of());
                when(products.findAllById(Set.of())).thenReturn(List.of());
            }
            case "without-erp-identity" -> doReturn(null).when(category).getErpCategoryId();
        }
        assertThatThrownBy(() -> pages.resolve("impresion")).isInstanceOf(CatalogNotFoundException.class);
        assertThatThrownBy(() -> pages.resolve("old-category")).isInstanceOf(CatalogNotFoundException.class);
        verify(products, never()).findAll(any(Specification.class), any(Sort.class));
    }

    @Test void historicalCategoryAliasReusesCurrentPublicDestination() {
        var result = (SeoPageService.Redirect) pages.resolve("old-category");
        assertThat(result.location().toString()).isEqualTo("https://store.solucionesmicaela.com/productos/impresion");
        category.setSlug("impresion-nueva");
        assertThat(((SeoPageService.Redirect) pages.resolve("old-category")).location().toString())
                .endsWith("/productos/impresion-nueva");
    }

    @Test void publicImageSelectionIgnoresInactiveImagesAndUsesPrimaryWithAbsoluteUrl() {
        product.getImages().add(image("inactive", true, false, 0, "products/inactive.png", "Oculta"));
        product.getImages().add(image("secondary", false, true, 0, "products/secondary.png", "Secundaria"));
        product.getImages().add(image("primary", true, true, 5, "products/primary.png", ""));
        media.setBaseUrl("https://media.example.test/media");
        var page = ((SeoPageService.Found) pages.resolve("banner")).page();
        assertThat(page.image()).isEqualTo(new SeoPageDtos.Image("https://media.example.test/media/products/primary.png", "Banner"));
        assertThat(JsonMapper.builder().build().writeValueAsString(page)).doesNotContain("inactive.png", "secondary.png", "storage-private");
    }

    @Test void imageWithoutPrimaryUsesDisplayOrderThenIdAsAngularDoes() {
        product.getImages().add(image("z", false, true, 4, "/images/z.png", "Z"));
        product.getImages().add(image("b", false, true, 1, "/images/b.png", "B"));
        product.getImages().add(image("a", false, true, 1, "/images/a.png", "A"));
        assertThat(((SeoPageService.Found) pages.resolve("banner")).page().image())
                .isEqualTo(new SeoPageDtos.Image("https://store.solucionesmicaela.com/images/a.png", "A"));
    }

    @Test void httpImageFallsBackToHttpsLogoWithoutChangingPublicCatalog() {
        String reference = "http://media.example.test/products/banner.png";
        product.getImages().add(image("primary", true, true, 0, reference, "Banner"));
        assertThat(((SeoPageService.Found) pages.resolve("banner")).page().image().url())
                .isEqualTo("https://store.solucionesmicaela.com/images/brand/logo-miqa3.png");
        assertThat(catalog.product("banner").images().getFirst().url()).isEqualTo(reference);
    }

    @Test void relativeImageResolvedThroughHttpMediaBaseFallsBackToHttpsLogo() {
        product.getImages().add(image("primary", true, true, 0, "products/banner.png", "Banner"));
        media.setBaseUrl("http://localhost:8081/media");
        assertThat(((SeoPageService.Found) pages.resolve("banner")).page().image().url())
                .isEqualTo("https://store.solucionesmicaela.com/images/brand/logo-miqa3.png");
    }

    @ParameterizedTest @ValueSource(strings = {"https://media.example.test/products/banner.png", "/images/banner.png", "images/banner.png"})
    void httpsAndRelativeImagesRetainTheirPublicUrl(String reference) {
        product.getImages().add(image("primary", true, true, 0, reference, "Banner"));
        String expected = reference.startsWith("https://") ? reference
                : "https://store.solucionesmicaela.com/" + reference.replaceFirst("^/", "");
        assertThat(((SeoPageService.Found) pages.resolve("banner")).page().image())
                .isEqualTo(new SeoPageDtos.Image(expected, "Banner"));
    }

    @Test void bodyFallsBackToShortDescriptionAndSeoOverridesStayIndependent() {
        product.setDescription(null); product.setSeoTitle(" Editorial SEO "); product.setSeoDescription(" SEO description ");
        var page = ((SeoPageService.Found) pages.resolve("banner")).page();
        assertThat(page.seoTitle()).isEqualTo("Editorial SEO");
        assertThat(page.seoDescription()).isEqualTo("SEO description");
        assertThat(page.bodyDescription()).isEqualTo("Resumen");
    }

    private ProductImage image(String id, boolean primary, boolean active, int order, String url, String alt) {
        var image = new ProductImage();
        image.setId(id); image.setProduct(product); image.setPrimaryImage(primary); image.setActive(active);
        image.setDisplayOrder(order); image.setUrl(url); image.setAltText(alt); image.setStorageKey("storage-private");
        return image;
    }
}
