package com.miqa.store.quote;

import com.miqa.store.catalog.ProductSaleType;
import com.miqa.store.erp.ErpCatalogContract;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class QuoteV2Dtos {
    private QuoteV2Dtos() {}
    public record Selection(@NotBlank @Size(max=64) String erpServiceId,
            @NotBlank @Size(max=128) String catalogRevision, @NotBlank @Size(max=64) String configurationVersion,
            @NotBlank @Size(max=64) String erpMaterialId, @Size(max=64) String erpModelId,
            @NotNull @Size(max=3) Map<@NotBlank @Size(max=16) String, @NotNull @Digits(integer=4, fraction=6) BigDecimal> measures) {}
    public record Item(@NotBlank @Size(max=64) String productId, ProductSaleType saleType,
            Integer packSize, @NotNull @DecimalMin("0.000001") @DecimalMax("1000000000") @Digits(integer=10, fraction=6) BigDecimal quantity,
            BigDecimal widthMeters, BigDecimal heightMeters, String materialId, List<String> extraIds,
            @Size(max=1000) String notes, @Valid Selection erp) {}
    public record Submission(@NotNull @Min(2) @Max(2) Integer schemaVersion,
            @NotNull @Valid QuoteRequestDtos.Contact contact, @Size(max=1000) String notes,
            @NotNull @Size(min=1,max=50) List<@NotNull @Valid Item> items) {
        @Override public String toString() { return "SubmissionV2[REDACTED]"; }
    }
    public record ErpSnapshot(int schemaVersion, String productId, String productName, String productSlug,
            String erpServiceId, String serviceName, ErpCatalogContract.Category category,
            String catalogRevision, String configurationVersion, String erpMaterialId, String materialName,
            String erpModelId, String modelName, BigDecimal quantity, Map<String, BigDecimal> measures,
            ErpCatalogContract.Configuration configuration, String notes) {}
    public record StoredItem(String productId, Object snapshot) {}
}
