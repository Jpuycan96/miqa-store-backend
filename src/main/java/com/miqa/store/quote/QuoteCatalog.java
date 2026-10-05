package com.miqa.store.quote;

import com.miqa.store.catalog.ProductSaleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import static com.miqa.store.quote.QuoteRequestDtos.*;

@Repository
public class QuoteCatalog {
    private final JdbcTemplate jdbc;
    public QuoteCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public QuoteSnapshot snapshot(Item item) {
        // New public submissions require ERP selection. Historical replay occurs before this call.
        // Never reinterpret a legacy snapshot using the current binding or catalog.
        throw QuoteRequestFailure.catalogChanged();
    }

    static QuoteSnapshot snapshot(Product product, Item item, QuoteSnapshot.Option material, List<QuoteSnapshot.Option> extras) {
        long minimum = product.minQuantity() == null ? 1 : product.minQuantity();
        int step = product.quantityStep() == null ? 1 : product.quantityStep();
        if (item.saleType() != product.saleType()) throw QuoteRequestFailure.catalogChanged();
        if (item.saleType() == ProductSaleType.PACK) {
            if (item.packSize() == null) throw QuoteRequestFailure.invalid();
            if (!item.packSize().equals(product.packSize())) throw QuoteRequestFailure.catalogChanged();
        } else if (item.packSize() != null) throw QuoteRequestFailure.invalid();
        if (item.quantity() < minimum || item.quantity() > MAX_QUANTITY) throw QuoteRequestFailure.invalid();
        boolean area = product.saleType() == ProductSaleType.AREA;
        if (area && (item.widthMeters() == null || item.heightMeters() == null)
                || !area && (item.widthMeters() != null || item.heightMeters() != null)) throw QuoteRequestFailure.invalid();
        return new QuoteSnapshot(1, product.id(), product.name(), product.slug(), product.category(), product.saleType(),
                item.quantity(), product.unitLabel(), product.packSize(), product.packLabel(), item.widthMeters(), item.heightMeters(),
                area ? item.widthMeters().multiply(item.heightMeters()) : null, material, extras,
                new QuoteSnapshot.Rules(minimum, step, MAX_QUANTITY, material != null,
                    area ? new BigDecimal("0.01") : null, area ? new BigDecimal("1000") : null, area ? 6 : null), item.notes());
    }

    record Product(String id, String name, String slug, QuoteSnapshot.Category category, ProductSaleType saleType,
                   String unitLabel, Integer packSize, String packLabel, Integer minQuantity, Integer quantityStep) {}
}
