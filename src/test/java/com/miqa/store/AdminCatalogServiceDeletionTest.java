package com.miqa.store;

import com.miqa.store.admin.AdminCatalogService;
import com.miqa.store.admin.AdminFailure;
import com.miqa.store.admin.ProductImageStorage;
import com.miqa.store.catalog.Category;
import com.miqa.store.catalog.CategoryRepository;
import com.miqa.store.catalog.CategorySlugAliasRepository;
import com.miqa.store.catalog.ProductRepository;
import com.miqa.store.config.MediaProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AdminCatalogServiceDeletionTest {
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CategorySlugAliasRepository aliases = mock(CategorySlugAliasRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AdminCatalogService service = new AdminCatalogService(categories, aliases, products,
            mock(EntityManager.class), new MediaProperties(), mock(ProductImageStorage.class));

    @Test void emptyCategoryReservesCurrentSlugDetachesAllAliasesAndDeletes() {
        var category = category();
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(aliases.existsById(category.getSlug())).thenReturn(false);

        service.deleteCategory(category.getId());

        verify(aliases).saveAndFlush(argThat(alias -> alias.getSlug().equals("categoria-prueba-nueva")
                && alias.getCategory() == category));
        verify(aliases).detachCategory(category.getId());
        verify(categories).delete(category);
        verify(categories).flush();
    }

    @Test void occupiedCategoryReturnsConflictWithoutChangingAliasesOrCategory() {
        var category = category();
        when(categories.findById(category.getId())).thenReturn(Optional.of(category));
        when(products.existsByCategoryId(category.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteCategory(category.getId()))
                .isInstanceOf(AdminFailure.class)
                .hasMessageContaining("productos asociados");

        verifyNoInteractions(aliases);
        verify(categories, never()).delete(any());
    }

    private Category category() {
        var category = new Category();
        category.setId("temporary-category");
        category.setName("Categoría Prueba Nueva");
        category.setSlug("categoria-prueba-nueva");
        return category;
    }
}
