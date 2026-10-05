package com.miqa.store.erp;

import org.springframework.jdbc.core.JdbcTemplate;
import java.text.Normalizer;
import java.util.*;

/** JDBC writes participate in the synchronization transaction and its advisory lock. */
final class ErpEditorialCatalog {
    private final JdbcTemplate jdbc;
    ErpEditorialCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void reconcile(ErpCatalogContract service) {
        // Shared with admin slug writers: category/product/alias namespaces overlap.
        jdbc.execute("SELECT pg_advisory_xact_lock(724193820127)");
        var category = service.categoria();
        var categories = jdbc.queryForList("SELECT id FROM categories WHERE erp_category_id = ?",
                String.class, category.erpCategoryId());
        String categoryId;
        if (categories.isEmpty()) {
            categoryId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO categories(id, erp_category_id, name, slug, active, display_order)
                    VALUES (?, ?, ?, ?, true, 0)
                    """, categoryId, category.erpCategoryId(), category.nombreReferencia(),
                    slug(category.nombreReferencia(), "categoria-erp-" + category.erpCategoryId()));
        } else {
            categoryId = categories.getFirst();
            jdbc.update("UPDATE categories SET name = ? WHERE id = ? AND name IS DISTINCT FROM ?",
                    category.nombreReferencia(), categoryId, category.nombreReferencia());
        }
        var principals = jdbc.queryForList("""
                SELECT product_id FROM product_erp_bindings WHERE erp_service_id = ? AND canonical
                """, String.class, service.erpServiceId());
        if (!principals.isEmpty()) {
            // Preserve every editorial field, including publication, slug and disabled binding.
            jdbc.update("UPDATE products SET category_id = ? WHERE id = ? AND category_id IS DISTINCT FROM ?",
                    categoryId, principals.getFirst(), categoryId);
            return;
        }
        // Never adopt an old binding or a matching name/slug/ID. Its meaning may differ.
        String productId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO products(id, category_id, name, slug, short_description, description, catalog_mode,
                    featured, published, display_order)
                VALUES (?, ?, ?, ?, '', '', 'ERP', false, false, 0)
                """, productId, categoryId, service.nombreReferencia(),
                slug(service.nombreReferencia(), "servicio-erp-" + service.erpServiceId()));
        jdbc.update("""
                INSERT INTO product_erp_bindings(product_id, erp_service_id, active, canonical)
                VALUES (?, ?, true, true)
                """, productId, service.erpServiceId());
    }

    private String slug(String name, String fallback) {
        String stem = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (stem.isEmpty()) stem = fallback;
        stem = stem.substring(0, Math.min(stem.length(), 110)).replaceAll("-+$", "");
        String candidate = stem;
        int suffix = 0;
        while (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM products WHERE slug = ? UNION ALL
                    SELECT 1 FROM categories WHERE slug = ? UNION ALL
                    SELECT 1 FROM category_slug_aliases WHERE slug = ?)
                """, Boolean.class, candidate, candidate, candidate))) {
            candidate = stem + "-" + (++suffix);
        }
        return candidate;
    }
}
