package com.miqa.store.catalog;

import com.miqa.store.catalog.CatalogDtos.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/public")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/categories")
    public List<CategoryDto> categories() { return catalog.categories(); }
    @GetMapping("/category-slug-redirects")
    public List<CategorySlugRedirectDto> categorySlugRedirects() { return catalog.categorySlugRedirects(); }
    @GetMapping("/products")
    public List<ProductDto> products(
            @RequestParam(required = false) @Size(max = 160) @Pattern(regexp = "[a-z0-9-]*") String category,
            @RequestParam(required = false) @Size(max = 120) String search,
            @RequestParam(required = false) Boolean featured) {
        return catalog.products(category, search, featured);
    }
    @GetMapping("/products/{slug}")
    public ProductDto product(@PathVariable @Size(max = 160) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {
        return catalog.product(slug);
    }
}
