package com.miqa.store.catalog;

import jakarta.persistence.*;
import java.time.Instant;

@MappedSuperclass
public abstract class TimestampedEntity {
    @Column(nullable = false, updatable = false) protected Instant createdAt;
    @Column(nullable = false) protected Instant updatedAt;
    @PrePersist protected void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
