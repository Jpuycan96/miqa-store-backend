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
            mock(EntityManager.class, RETURNS_DEEP_STUBS), new MediaProperties(), mock(ProductImageStorage.class));

    @Test void categoryDeletionIsRetiredWithoutChangingRowsOrAliases() {
        assertThatThrownBy(() -> service.deleteCategory("temporary-category")).isInstanceOf(AdminFailure.class);
        verifyNoInteractions(categories,aliases,products);
    }
}
