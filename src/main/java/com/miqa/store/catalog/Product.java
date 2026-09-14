package com.miqa.store.catalog;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "products")
public class Product extends TimestampedEntity {
    @Id @Column(length = 64) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false) private Category category;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, unique = true, length = 160) private String slug;
    @Column(nullable = false, length = 500) private String shortDescription;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ProductSaleType saleType;
    @Column(nullable = false, length = 40) private String unitLabel;
    private Integer packSize;
    @Column(length = 40) private String packLabel;
    private Integer minQuantity;
    private Integer quantityStep;
    @Column(nullable = false) private boolean featured;
    @Column(nullable = false) private boolean published;
    @Column(nullable = false) private int displayOrder;
    @Column(length = 200) private String seoTitle;
    @Column(length = 500) private String seoDescription;
    @OneToMany(mappedBy = "product") @OrderBy("displayOrder ASC, id ASC") @BatchSize(size = 100)
    private List<ProductMaterial> materials = new ArrayList<>();
    @OneToMany(mappedBy = "product") @OrderBy("displayOrder ASC, id ASC") @BatchSize(size = 100)
    private List<ProductExtra> extras = new ArrayList<>();
    @OneToMany(mappedBy = "product") @OrderBy("displayOrder ASC, id ASC") @BatchSize(size = 100)
    private List<ProductImage> images = new ArrayList<>();
    public Product() {}
    public String getId() { return id; }
    public Category getCategory() { return category; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getShortDescription() { return shortDescription; }
    public String getDescription() { return description; }
    public ProductSaleType getSaleType() { return saleType; }
    public String getUnitLabel() { return unitLabel; }
    public Integer getPackSize() { return packSize; }
    public String getPackLabel() { return packLabel; }
    public Integer getMinQuantity() { return minQuantity; }
    public Integer getQuantityStep() { return quantityStep; }
    public boolean isFeatured() { return featured; }
    public boolean isPublished() { return published; }
    public int getDisplayOrder() { return displayOrder; }
    public String getSeoTitle() { return seoTitle; }
    public String getSeoDescription() { return seoDescription; }
    public List<ProductMaterial> getMaterials() { return materials; }
    public List<ProductExtra> getExtras() { return extras; }
    public List<ProductImage> getImages() { return images; }
    public void setId(String value) { this.id = value; }
    public void setCategory(Category value) { this.category = value; }
    public void setName(String value) { this.name = value; }
    public void setSlug(String value) { this.slug = value; }
    public void setShortDescription(String value) { this.shortDescription = value; }
    public void setDescription(String value) { this.description = value; }
    public void setSaleType(ProductSaleType value) { this.saleType = value; }
    public void setUnitLabel(String value) { this.unitLabel = value; }
    public void setPackSize(Integer value) { this.packSize = value; }
    public void setPackLabel(String value) { this.packLabel = value; }
    public void setMinQuantity(Integer value) { this.minQuantity = value; }
    public void setQuantityStep(Integer value) { this.quantityStep = value; }
    public void setFeatured(boolean value) { this.featured = value; }
    public void setPublished(boolean value) { this.published = value; }
    public void setDisplayOrder(int value) { this.displayOrder = value; }
    public void setSeoTitle(String value) { this.seoTitle = value; }
    public void setSeoDescription(String value) { this.seoDescription = value; }
}
