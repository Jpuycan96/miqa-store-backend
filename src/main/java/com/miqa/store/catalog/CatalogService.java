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
@Transactional(readOnly = true)
public class CatalogService {
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final MediaProperties media;
    public CatalogService(CategoryRepository categories, ProductRepository products, MediaProperties media) {
        this.categories = categories;
        this.products = products;
        this.media = media;
    }
    public List<CategoryDto> categories() {
        return categories.findByActiveTrueOrderByDisplayOrderAscIdAsc().stream().map(this::categoryDto).toList();
    }
    public List<ProductDto> products(String category, String search, Boolean featured) {
        Specification<Product> specification = (root, query, cb) -> cb.and(
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
                category.getCatalogHeadline(), category.getCatalogDescription(), category.getDisplayOrder());
    }
    private ProductDto productDto(Product product) {
        var images = product.getImages().stream().filter(ProductImage::isActive).map(image -> new ImageDto(image.getId(), media.publicUrl(image.getUrl()), image.getAltText(), image.isPrimaryImage(), image.getDisplayOrder())).toList();
        String primary = images.stream().filter(ImageDto::primaryImage).findFirst().or(() -> images.stream().findFirst()).map(ImageDto::url).orElse("");
        return new ProductDto(product.getId(), product.getSlug(), product.getName(), product.getShortDescription(), product.getDescription(),
                product.getCategory().getSlug(), categoryDto(product.getCategory()), primary,
                images.stream().filter(image -> !image.url().equals(primary)).map(ImageDto::url).toList(), images,
                product.isFeatured(), product.isPublished(), product.getSaleType(), product.getUnitLabel(), product.getPackSize(), product.getPackLabel(),
                product.getMinQuantity(), product.getQuantityStep(),
                product.getMaterials().stream().filter(ProductMaterial::isActive).map(option -> new OptionDto(option.getId(), option.getName())).toList(),
                product.getExtras().stream().filter(ProductExtra::isActive).map(option -> new OptionDto(option.getId(), option.getName())).toList());
    }
}
