package com.miqa.store.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {
    Optional<Product> findBySlugAndPublishedTrueAndCategoryActiveTrue(String slug);
    boolean existsBySlugAndIdNot(String slug, String id);
    boolean existsBySlug(String slug);
    boolean existsByCategoryId(String categoryId);
}
