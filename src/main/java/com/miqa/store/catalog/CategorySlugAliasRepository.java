package com.miqa.store.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CategorySlugAliasRepository extends JpaRepository<CategorySlugAlias, String> {
    @Query("select alias from CategorySlugAlias alias join fetch alias.category category where category.active = true order by alias.slug")
    List<CategorySlugAlias> findPublicRedirects();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CategorySlugAlias alias set alias.category = null where alias.category.id = :categoryId")
    int detachCategory(@Param("categoryId") String categoryId);
}
