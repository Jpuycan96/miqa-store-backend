package com.miqa.store.catalog;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "categories")
@BatchSize(size = 100)
public class Category extends TimestampedEntity {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, unique = true, length = 160) private String slug;
    @Column(columnDefinition = "text") private String description;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false) private int displayOrder;
    public Category() {}
    public String getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
    public void setId(String value) { this.id = value; }
    public void setName(String value) { this.name = value; }
    public void setSlug(String value) { this.slug = value; }
    public void setDescription(String value) { this.description = value; }
    public void setActive(boolean value) { this.active = value; }
    public void setDisplayOrder(int value) { this.displayOrder = value; }
}
