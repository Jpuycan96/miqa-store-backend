package com.miqa.store.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "product_materials")
public class ProductMaterial {
    @Id @Column(length = 64) private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "product_id", nullable = false) private Product product;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false) private int displayOrder;
    public ProductMaterial() {}
    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
    public void setId(String value) { this.id = value; }
    public void setProduct(Product value) { this.product = value; }
    public void setName(String value) { this.name = value; }
    public void setActive(boolean value) { this.active = value; }
    public void setDisplayOrder(int value) { this.displayOrder = value; }
}
