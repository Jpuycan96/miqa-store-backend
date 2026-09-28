package com.miqa.store.quote;

import com.miqa.store.catalog.ProductSaleType;
import java.math.BigDecimal;
import java.util.List;

/** Server-owned, versioned historical value; no live catalog references or prices. */
public record QuoteSnapshot(
        int schemaVersion, String productId, String productName, String productSlug,
        Category category, ProductSaleType saleType, long quantity, String unitLabel,
        Integer packSize, String packLabel, BigDecimal widthMeters, BigDecimal heightMeters,
        BigDecimal areaSquareMeters, Option material, List<Option> extras, Rules rules, String notes) {
    public record Category(String id, String name, String slug) {}
    public record Option(String id, String name) {}
    public record Rules(long minQuantity, int quantityStep, long maxQuantity, boolean materialRequired,
                        BigDecimal minDimensionMeters, BigDecimal maxDimensionMeters, Integer dimensionDecimalPlaces) {}
    @Override public String toString() { return "QuoteSnapshot[REDACTED]"; }
}
