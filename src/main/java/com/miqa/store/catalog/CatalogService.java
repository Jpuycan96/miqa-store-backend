package com.miqa.store.catalog;

import com.miqa.store.catalog.CatalogDtos.*;
import com.miqa.store.error.CatalogNotFoundException;
import com.miqa.store.config.MediaProperties;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;

@Service
@Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class CatalogService {
    private final CategoryRepository categories;
    private final CategorySlugAliasRepository categoryAliases;
    private final ProductRepository products;
    private final MediaProperties media;
    private final com.miqa.store.erp.PublicErpConfiguration configurations;
    public CatalogService(CategoryRepository categories, CategorySlugAliasRepository categoryAliases, ProductRepository products, MediaProperties media,
                          com.miqa.store.erp.PublicErpConfiguration configurations) {
        this.categories = categories;
        this.categoryAliases = categoryAliases;
        this.products = products;
        this.media = media;
        this.configurations = configurations;
    }
    public List<CategoryDto> categories() {
        var visible = configurations.visibleProductIds();
        var categoryIds = products.findAllById(visible).stream().map(p -> p.getCategory().getId()).collect(java.util.stream.Collectors.toSet());
        return categories.findByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
                .filter(c -> c.getErpCategoryId() != null && categoryIds.contains(c.getId())).map(this::categoryDto).toList();
    }
    public List<CategorySlugRedirectDto> categorySlugRedirects() {
        var visible = categories().stream().map(CategoryDto::id).collect(java.util.stream.Collectors.toSet());
        return categoryAliases.findPublicRedirects().stream().filter(a -> visible.contains(a.getCategory().getId()))
                .map(alias -> new CategorySlugRedirectDto(alias.getSlug(), alias.getCategory().getSlug())).toList();
    }
    public List<ProductDto> products(String category, String search, Boolean featured) {
        var visible = configurations.visibleProductIds();
        if (visible.isEmpty()) return List.of();
        Specification<Product> specification = (root, query, cb) -> cb.and(root.get("id").in(visible),
                cb.isTrue(root.get("published")), cb.isTrue(root.get("category").get("active")));
        if (category != null && !category.isBlank()) specification = specification.and(
                (root, query, cb) -> cb.equal(root.get("category").get("slug"), category.trim()));
        if (search != null && !search.isBlank()) {
            String literal = search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + literal + "%", '\\'));
        }
        if (featured != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("featured"), featured));
        return products.findAll(specification, Sort.by("displayOrder").ascending().and(Sort.by("id"))).stream().map(this::productDto).toList();
    }
    public ProductDto product(String slug) {
        return productDto(products.findBySlugAndPublishedTrueAndCategoryActiveTrue(slug)
                .orElseThrow(() -> new CatalogNotFoundException("Producto no disponible")));
    }
    private CategoryDto categoryDto(Category category) {
        return new CategoryDto(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
                category.getCatalogHeadline(), category.getCatalogDescription(), category.getDisplayOrder(), category.getErpCategoryId());
    }
    private ProductDto productDto(Product product) {
        var images = product.getImages().stream().filter(ProductImage::isActive).map(image -> new ImageDto(image.getId(), media.publicUrl(image.getUrl()), image.getAltText(), image.isPrimaryImage(), image.getDisplayOrder())).toList();
        String primary = images.stream().filter(ImageDto::primaryImage).findFirst().or(() -> images.stream().findFirst()).map(ImageDto::url).orElse("");
        return new ProductDto(product.getId(), product.getSlug(), product.getName(), product.getShortDescription(), product.getDescription(),
                product.getCategory().getSlug(), categoryDto(product.getCategory()), primary,
                images.stream().filter(image -> !image.url().equals(primary)).map(ImageDto::url).toList(), images,
                product.isFeatured(), product.isPublished(), product.getSaleType(), product.getUnitLabel(), product.getPackSize(), product.getPackLabel(),
                product.getMinQuantity(), product.getQuantityStep(),
                List.of(), List.of(),
                configurations.configuration(product.getId()), product.getSeoTitle(), product.getSeoDescription());
    }
}
