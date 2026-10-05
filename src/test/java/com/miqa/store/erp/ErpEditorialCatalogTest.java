package com.miqa.store.erp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErpEditorialCatalogTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ErpEditorialCatalog editorial = new ErpEditorialCatalog(jdbc);
    private ErpCatalogContract contract(String name) {
        return new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON.replace("SELLOS", name) + "]").getFirst();
    }

    @Test void newServiceCreatesIndependentUuidIdentitiesAndDraftWithoutLegacyConfiguration() {
        editorial.reconcile(contract("Banner"));
        var category = ArgumentCaptor.forClass(String.class);
        var product = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("INSERT INTO categories"), category.capture(), eq("3"), eq("ERP category"), eq("erp-category"));
        verify(jdbc).update(contains("'ERP', false, false"), product.capture(), eq(category.getValue()), eq("Banner"), eq("banner"));
        assertThat(UUID.fromString(category.getValue())).isNotNull();
        assertThat(UUID.fromString(product.getValue())).isNotNull();
        assertThat(product.getValue()).isNotIn("17", "banner", category.getValue());
        verify(jdbc).update(contains("true, true"), eq(product.getValue()), eq("17"));
        verify(jdbc, never()).update(contains("product_materials"), any(Object[].class));
    }

    @Test void existingCanonicalIncludingDisabledOnlyReceivesErpCategoryRelationship() {
        when(jdbc.queryForList(contains("FROM categories"), eq(String.class), eq("3"))).thenReturn(List.of("category-uuid"));
        when(jdbc.queryForList(contains("AND canonical"), eq(String.class), eq("17"))).thenReturn(List.of("product-uuid"));
        editorial.reconcile(contract("Nuevo nombre"));
        editorial.reconcile(contract("Otro nombre"));
        verify(jdbc, times(2)).update(startsWith("UPDATE products SET category_id"), eq("category-uuid"), eq("product-uuid"), eq("category-uuid"));
        verify(jdbc, never()).update(contains("INSERT INTO products"), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT INTO product_erp_bindings"), any(Object[].class));
        verify(jdbc, never()).update(contains("SET slug"), any(Object[].class));
        verify(jdbc, never()).update(contains("SET active"), any(Object[].class));
    }

    @Test void collisionReservesLegacyCategoryAndAliasSlugsWithoutAdoption() {
        when(jdbc.queryForObject(contains("category_slug_aliases"), eq(Boolean.class), eq("banner"), eq("banner"), eq("banner"))).thenReturn(true);
        when(jdbc.queryForObject(contains("category_slug_aliases"), eq(Boolean.class), eq("banner-1"), eq("banner-1"), eq("banner-1"))).thenReturn(true);
        editorial.reconcile(contract("Banner"));
        verify(jdbc).update(contains("INSERT INTO products"), anyString(), anyString(), eq("Banner"), eq("banner-2"));
        verify(jdbc, never()).queryForList(contains("FROM products"), eq(String.class), anyString());
        verify(jdbc, never()).update(startsWith("UPDATE products"), any(Object[].class));
    }

    @Test void historicalBindingsAreNotPromotedOrReassigned() {
        editorial.reconcile(contract("Banner"));
        verify(jdbc).queryForList(contains("WHERE erp_service_id = ? AND canonical"), eq(String.class), eq("17"));
        verify(jdbc, never()).update(contains("UPDATE product_erp_bindings"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO product_erp_bindings"), anyString(), eq("17"));
    }

    @Test void categoryIdentityAndSlugSurviveNameChanges() {
        when(jdbc.queryForList(contains("FROM categories"), eq(String.class), eq("3"))).thenReturn(List.of("same-category"));
        editorial.reconcile(contract("Banner"));
        verify(jdbc).update(startsWith("UPDATE categories SET name"), eq("ERP category"), eq("same-category"), eq("ERP category"));
        verify(jdbc, never()).update(contains("INSERT INTO categories"), any(Object[].class));
    }

    @Test void conflictingCategoryNamesOrMissingCategoryRejectEntireListing() {
        var one = contract("Banner");
        var two = new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON
                .replace("\"erpServiceId\":\"17\"", "\"erpServiceId\":\"18\"")
                .replace("ERP category", "Different category name") + "]").getFirst();
        assertThatThrownBy(() -> ErpCatalogValidation.listing(List.of(one, two))).isInstanceOf(ErpCatalogFailure.class);
        var missing = new ErpCatalogClient("", "").decode("[" + ErpCatalogTest.JSON
                .replace("{\"erpCategoryId\":\"3\",\"nombreReferencia\":\"ERP category\"}", "null") + "]").getFirst();
        assertThatThrownBy(() -> ErpCatalogValidation.listing(List.of(missing))).isInstanceOf(ErpCatalogFailure.class);
    }
}
