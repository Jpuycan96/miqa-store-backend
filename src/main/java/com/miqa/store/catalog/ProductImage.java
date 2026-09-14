package com.miqa.store.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "product_images")
public class ProductImage {
    @Id @Column(length = 64) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Column(nullable = false, length = 2048) private String url;
    @Column(nullable = false, length = 300) private String altText;
    @Column(nullable = false) private boolean primaryImage;
    @Column(nullable = false) private int displayOrder;
    public ProductImage() {}
    public String getId() { return id; }
    public String getUrl() { return url; }
    public String getAltText() { return altText; }
    public boolean isPrimaryImage() { return primaryImage; }
    public int getDisplayOrder() { return displayOrder; }
    public void setId(String value) { this.id = value; }
    public void setProduct(Product value) { this.product = value; }
    public void setUrl(String value) { this.url = value; }
    public void setAltText(String value) { this.altText = value; }
    public void setPrimaryImage(boolean value) { this.primaryImage = value; }
    public void setDisplayOrder(int value) { this.displayOrder = value; }
}
