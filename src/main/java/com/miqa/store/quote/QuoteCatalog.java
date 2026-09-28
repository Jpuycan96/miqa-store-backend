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
        var products = jdbc.query("""
                SELECT p.*, c.name AS category_name, c.slug AS category_slug
                FROM products p JOIN categories c ON c.id = p.category_id
                WHERE p.id = ? AND p.published AND c.active
                """, (rs, row) -> new Product(rs.getString("id"), rs.getString("name"), rs.getString("slug"),
                    new QuoteSnapshot.Category(rs.getString("category_id"), rs.getString("category_name"), rs.getString("category_slug")),
                    ProductSaleType.valueOf(rs.getString("sale_type")), rs.getString("unit_label"),
                    rs.getObject("pack_size", Integer.class), rs.getString("pack_label"),
                    rs.getObject("min_quantity", Integer.class), rs.getObject("quantity_step", Integer.class)), item.productId());
        if (products.isEmpty()) throw QuoteRequestFailure.catalogChanged();
        Product product = products.getFirst();
        if (item.materialId() == null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM product_materials WHERE product_id = ? AND active)", Boolean.class, product.id()))) {
            // Same rule as createQuoteItem: an active material must be selected if the product offers any.
            throw QuoteRequestFailure.invalid();
        }
        var material = item.materialId() == null ? null : option("product_materials", product.id(), item.materialId());
        var extras = item.extraIds().stream().map(id -> option("product_extras", product.id(), id)).toList();
        return snapshot(product, item, material, extras);
    }

    private QuoteSnapshot.Option option(String table, String productId, String id) {
        // Table names are constants above, never taken from user input.
        var options = jdbc.query("SELECT id, name FROM " + table + " WHERE product_id = ? AND id = ? AND active",
                (rs, row) -> new QuoteSnapshot.Option(rs.getString("id"), rs.getString("name")), productId, id);
        if (options.isEmpty()) throw QuoteRequestFailure.catalogChanged();
        return options.getFirst();
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
