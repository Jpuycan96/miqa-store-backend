package com.miqa.store.erp;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class ErpCatalogDtos {
    private ErpCatalogDtos() {}
    public record SyncStatus(String outcome, Instant attemptedAt, Instant succeededAt, int received, int changed, int missing) {}
    // The saved ERP payload is historical when pending; available is the effective LOCAL value.
    public record Projection(String erpServiceId, boolean available, String syncState,
                             Instant lastSyncedAt, ErpCatalogContract lastKnownErp) {}
    public record BindingInput(@NotBlank @Size(max = 64) @Pattern(regexp = "[1-9][0-9]*") String erpServiceId,
                               @NotNull Boolean active) {}
    public record ProductErpBinding(String productId, String erpServiceId, boolean active, String state,
                                   Instant createdAt, Instant updatedAt, Instant lastSyncedAt, boolean canonical) {
        public ProductErpBinding(String productId, String erpServiceId, boolean active, String state,
                Instant createdAt, Instant updatedAt, Instant lastSyncedAt) {
            this(productId, erpServiceId, active, state, createdAt, updatedAt, lastSyncedAt, false);
        }
    }
}
