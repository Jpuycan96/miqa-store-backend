package com.miqa.store.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "category_slug_aliases")
public class CategorySlugAlias {
    @Id @Column(length = 160) private String slug;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "category_id") private Category category;
    public CategorySlugAlias() {}
    public CategorySlugAlias(String slug, Category category) { this.slug = slug; this.category = category; }
    public String getSlug() { return slug; }
    public Category getCategory() { return category; }
}
