package com.miqa.store.catalog;

import com.miqa.store.error.CatalogNotFoundException;
import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SeoPageService {
    private static final String ORIGIN = "https://store.solucionesmicaela.com";
    private static final String LOGO = ORIGIN + "/images/brand/logo-miqa3.png";
    private static final String PRODUCT_DESCRIPTION = "Explora los productos y soluciones gráficas disponibles de MIQA.";
    private final CatalogService catalog;

    public sealed interface Resolution permits Found, Redirect {}
    public record Found(SeoPageDtos.Page page) implements Resolution {}
    public record Redirect(URI location) implements Resolution {}

    public SeoPageService(CatalogService catalog) { this.catalog = catalog; }

    public Resolution resolve(String slug) {
        if (!validSlug(slug)) throw unavailable();
        // Same namespace/precedence as Angular; visibility belongs entirely to CatalogService.
        var categories = catalog.categories();
        var category = categories.stream().filter(c -> slug.equals(c.slug())).findFirst();
        if (category.isPresent()) return new Found(categoryPage(category.get()));
        try {
            return new Found(productPage(catalog.product(slug)));
        } catch (CatalogNotFoundException missingProduct) {
            // Only a genuine absence permits alias lookup; query failures must remain HTTP 500.
            var alias = catalog.categorySlugRedirects().stream()
                    .filter(a -> slug.equals(a.oldSlug()) && !slug.equals(a.currentSlug()))
                    .filter(a -> categories.stream().anyMatch(c -> a.currentSlug().equals(c.slug())))
                    .findFirst();
            if (alias.isPresent()) return new Redirect(URI.create(pageUrl(alias.get().currentSlug())));
            throw unavailable();
        }
    }

    private SeoPageDtos.Page categoryPage(CatalogDtos.CategoryDto category) {
        String url = pageUrl(category.slug());
        String editorial = text(category.catalogDescription());
        String description = editorial.isEmpty()
                ? "Conoce las opciones de " + category.name() + " de MIQA en Trujillo y solicita una cotización para tu proyecto."
                : editorial + " Conoce las opciones de " + category.name().toLowerCase(Locale.forLanguageTag("es"))
                        + " de MIQA en Trujillo.";
        var links = new LinkedHashMap<String, SeoPageDtos.Link>();
        for (var product : catalog.products(category.slug(), null, null)) {
            String productUrl = pageUrl(product.slug());
            if (!productUrl.equals(url)) links.putIfAbsent(productUrl, new SeoPageDtos.Link(product.name(), productUrl));
        }
        return new SeoPageDtos.Page(SeoPageDtos.PageType.CATEGORY, category.name(), category.slug(),
                category.name() + " en Trujillo | MIQA", description,
                firstText(category.catalogDescription(), category.description()),
                new SeoPageDtos.Image(LOGO, category.name()), breadcrumbs(category.name(), url),
                List.copyOf(links.values()), url);
    }

    private SeoPageDtos.Page productPage(CatalogDtos.ProductDto product) {
        String url = pageUrl(product.slug());
        return new SeoPageDtos.Page(SeoPageDtos.PageType.PRODUCT, product.name(), product.slug(),
                firstText(product.seoTitle(), product.name() + " | MIQA"),
                firstText(product.seoDescription(), product.shortDescription(), product.description(), PRODUCT_DESCRIPTION),
                firstText(product.description(), product.shortDescription()), productImage(product),
                breadcrumbs(product.name(), url), List.of(), url);
    }

    private SeoPageDtos.Image productImage(CatalogDtos.ProductDto product) {
        // Match Angular productImages(): primary, displayOrder, then ID. IDs never leave this service.
        var image = product.images().stream().sorted(Comparator
                .comparing(CatalogDtos.ImageDto::primaryImage).reversed()
                .thenComparingInt(CatalogDtos.ImageDto::displayOrder).thenComparing(CatalogDtos.ImageDto::id))
                .findFirst();
        if (image.isPresent()) return new SeoPageDtos.Image(imageUrl(image.get().url()),
                firstText(image.get().altText(), product.name()));
        String reference = firstText(product.image());
        if (reference.isEmpty()) reference = product.gallery().stream().filter(u -> !text(u).isEmpty()).findFirst().orElse(LOGO);
        return new SeoPageDtos.Image(imageUrl(reference), product.name());
    }

    private static List<SeoPageDtos.Link> breadcrumbs(String name, String url) {
        return List.of(new SeoPageDtos.Link("Inicio", ORIGIN + "/"),
                new SeoPageDtos.Link("Productos", ORIGIN + "/productos"), new SeoPageDtos.Link(name, url));
    }

    private static String imageUrl(String reference) {
        if (text(reference).isEmpty()) return LOGO;
        URI uri = URI.create(reference);
        if (uri.isAbsolute()) {
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalStateException("Invalid public image");
            if ("http".equalsIgnoreCase(uri.getScheme())) return LOGO;
            return reference;
        }
        if (reference.startsWith("//") || reference.contains("\\") || uri.getPath().contains(".."))
            throw new IllegalStateException("Invalid public image");
        return ORIGIN + (reference.startsWith("/") ? reference : "/" + reference);
    }

    private static String pageUrl(String slug) {
        if (!validSlug(slug)) throw new IllegalStateException("Invalid public slug");
        return ORIGIN + "/productos/" + slug;
    }
    private static boolean validSlug(String slug) {
        return slug != null && slug.length() <= 160 && slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String firstText(String... values) {
        for (String value : values) if (!text(value).isEmpty()) return text(value);
        return "";
    }
    private static CatalogNotFoundException unavailable() { return new CatalogNotFoundException("Recurso no disponible"); }
}
