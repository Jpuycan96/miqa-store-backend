package com.miqa.store.pricing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class PricingDtos {
    private PricingDtos() {}
    public enum Status {
        PRICE_AVAILABLE, QUOTE_REQUIRED, CONFIGURATION_STALE, CONFIGURATION_INVALID, TEMPORARILY_UNAVAILABLE;
        public int httpStatus() { return switch (this) {
            case PRICE_AVAILABLE, QUOTE_REQUIRED -> 200;
            case CONFIGURATION_STALE -> 409;
            case CONFIGURATION_INVALID -> 422;
            case TEMPORARILY_UNAVAILABLE -> 503;
        }; }
    }
    public record Input(@NotBlank @Size(max=64) String productId,
            @NotNull @DecimalMin("0.000001") @DecimalMax("1000000000") @Digits(integer=10,fraction=6) BigDecimal quantity,
            @NotBlank @Size(max=64) String erpMaterialId, @Size(max=64) String erpModelId,
            @NotNull @Size(max=3) Map<@NotBlank @Size(max=16) String,@NotNull @Digits(integer=4,fraction=6) BigDecimal> measures) {}
    public record BillableBase(String quantity, String unit) {}
    public record PublicResult(Status status, String amount, String currency, Boolean includesIgv,
            String scope, String quoteMode, BillableBase billableBase) {}
    public record Historical(Status status, String amount, String currency, Boolean includesIgv,
            String scope, String quoteMode, BillableBase billableBase, String pricingRevision, Instant evaluatedAt) {
        public PublicResult publicResult() { return new PublicResult(status,amount,currency,includesIgv,scope,quoteMode,billableBase); }
        public static Historical state(Status status) { return new Historical(status,null,null,null,null,null,null,null,null); }
    }
}
