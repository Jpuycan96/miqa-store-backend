package com.miqa.store.quote;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.miqa.store.catalog.ProductSaleType;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class QuoteRequestDtos {
    private QuoteRequestDtos() {}
    public static final long MAX_QUANTITY = 1_000_000_000L;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 32) @Pattern(regexp = "\\+?[0-9 ()-]+") String phone,
            @Email @Size(max = 254) String email) {
        @Override public String toString() { return "Contact[REDACTED]"; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @NotBlank @Size(max = 64) String productId,
            @NotNull ProductSaleType saleType,
            @Positive Integer packSize,
            @NotNull @Min(1) @Max(MAX_QUANTITY)
            @JsonDeserialize(using = StrictQuantityDeserializer.class) Long quantity,
            @DecimalMin("0.01") @DecimalMax("1000") @Digits(integer = 4, fraction = 6) BigDecimal widthMeters,
            @DecimalMin("0.01") @DecimalMax("1000") @Digits(integer = 4, fraction = 6) BigDecimal heightMeters,
            @Size(min = 1, max = 64) String materialId,
            @Size(max = 50) List<@NotBlank @Size(max = 64) String> extraIds,
            @Size(max = 1000) String notes) {
        @Override public String toString() { return "Item[REDACTED]"; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Submission(
            @NotNull @Valid Contact contact,
            @Size(max = 1000) String notes,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Item> items) {
        @Override public String toString() { return "Submission[REDACTED]"; }
    }

    public record Confirmation(String reference, Instant receivedAt, String confirmation) {}
    public record Result(Confirmation confirmation, boolean replay) {}
}
