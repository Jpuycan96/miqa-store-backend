package com.miqa.store.catalog;

import java.util.List;

public final class CatalogDtos {
    private CatalogDtos() {}
    public record CategoryDto(String id, String name, String slug, String description, int displayOrder) {}
    public record OptionDto(String id, String name) {}
    public record ImageDto(String id, String url, String altText, boolean primaryImage, int displayOrder) {}
    public record ProductDto(String id, String slug, String name, String shortDescription, String description,
            String categorySlug, CategoryDto category, String image, List<String> gallery, List<ImageDto> images,
            boolean featured, boolean published, ProductSaleType saleType, String unitLabel,
            Integer packSize, String packLabel, Integer minQuantity, Integer step,
            List<OptionDto> materials, List<OptionDto> extras) {}
}
