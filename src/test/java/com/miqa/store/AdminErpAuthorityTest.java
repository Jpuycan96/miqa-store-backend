package com.miqa.store;

import com.miqa.store.admin.*;
import com.miqa.store.catalog.*;
import com.miqa.store.config.MediaProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminErpAuthorityTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final EntityManager em = mock(EntityManager.class, RETURNS_DEEP_STUBS);
    private final AdminCatalogService admin = new AdminCatalogService(categories, mock(CategorySlugAliasRepository.class),
            products, em, new MediaProperties(), mock(ProductImageStorage.class));
    private Product product() {
        var p = new Product(); p.setId("uuid"); p.setCatalogMode("ERP");
        var c = new Category(); c.setId("category-uuid"); p.setCategory(c);
        when(products.findById("uuid")).thenReturn(Optional.of(p)); return p;
    }
    private AdminDtos.ProductInput input(String category, ProductSaleType type) {
        return new AdminDtos.ProductInput(category, "Titulo comercial", "url-estable", "Breve", "Editorial",
                type, null, null, null, null, null, true, true, 2, "SEO", "SEO description");
    }
    @Test void editorialFieldsRemainEditableAndModeIsExposed() {
        var p = product();
        var result = admin.saveProduct("uuid", input("category-uuid", null));
        assertThat(result.catalogMode()).isEqualTo("ERP");
        assertThat(result.name()).isEqualTo("Titulo comercial");
        assertThat(result.description()).isEqualTo("Editorial");
        assertThat(result.slug()).isEqualTo("url-estable");
        assertThat(result.seoTitle()).isEqualTo("SEO");
        assertThat(p.isPublished()).isTrue();
        assertThat(p.getSaleType()).isNull();
    }
    @Test void technicalConfigurationAndCategoryChangesAreRejected() {
        product();
        assertThatThrownBy(() -> admin.saveProduct("uuid", input("category-uuid", ProductSaleType.AREA))).isInstanceOf(AdminFailure.class);
        assertThatThrownBy(() -> admin.saveProduct("uuid", input("other-category", null))).isInstanceOf(AdminFailure.class);
        verify(em, never()).flush();
    }
    @Test void allLegacyOptionMutationRoutesAreProtected() {
        product();
        var option = new AdminDtos.OptionInput("Material", true, 0);
        for (Runnable operation : java.util.List.<Runnable>of(
                () -> admin.savematerials("uuid", null, option), () -> admin.savematerials("uuid", "old", option),
                () -> admin.activematerials("uuid", "old", false), () -> admin.deleteMaterial("uuid", "old"),
                () -> admin.saveextras("uuid", null, option), () -> admin.saveextras("uuid", "old", option),
                () -> admin.activeextras("uuid", "old", false))) {
            assertThatThrownBy(operation::run).isInstanceOf(AdminFailure.class);
        }
    }
    @Test void erpCategoryStructureCannotBeEditedDeactivatedOrDeleted() {
        var category = mock(Category.class);
        when(category.getErpCategoryId()).thenReturn("3");
        when(categories.findById("category")).thenReturn(Optional.of(category));
        assertThatThrownBy(() -> admin.activeCategory("category", false)).isInstanceOf(AdminFailure.class);
        assertThatThrownBy(() -> admin.deleteCategory("category")).isInstanceOf(AdminFailure.class);
        assertThatThrownBy(() -> admin.saveCategory("category", new AdminDtos.CategoryInput("Another name", null, null, null, true, 0)))
                .isInstanceOf(AdminFailure.class);
    }

    @Test void erpCategoryEditorialUpdatePreservesIdentityAndRelationships() {
        var category = spy(new Category());
        category.setId("category");category.setName("ERP name");category.setSlug("stable-slug");
        doReturn("3").when(category).getErpCategoryId();
        when(categories.findById("category")).thenReturn(Optional.of(category));
        var result=admin.saveCategory("category",new AdminDtos.CategoryInput("ERP name","Description"," Headline "," Summary ",false,7));
        assertThat(result.id()).isEqualTo("category");assertThat(result.erpCategoryId()).isEqualTo("3");
        assertThat(result.name()).isEqualTo("ERP name");assertThat(result.slug()).isEqualTo("stable-slug");
        assertThat(result.description()).isEqualTo("Description");assertThat(result.catalogHeadline()).isEqualTo("Headline");
        assertThat(result.catalogDescription()).isEqualTo("Summary");assertThat(result.active()).isFalse();assertThat(result.displayOrder()).isEqualTo(7);
        verify(em).flush();verify(em,never()).persist(any());verifyNoInteractions(products);
    }

    @Test void categoryInputRejectsProtectedOrUnknownJsonFields() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String field:java.util.List.of("id","erpCategoryId","erp_category_id","slug","products","categoryId")){
            String json="{\"name\":\"ERP name\",\"active\":true,\"displayOrder\":0,\""+field+"\":\"tampered\"}";
            assertThatThrownBy(()->mapper.readValue(json,AdminDtos.CategoryInput.class)).isInstanceOf(RuntimeException.class);
        }
    }
}
