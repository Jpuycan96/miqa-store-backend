package com.miqa.store.quote;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.miqa.store.quote.QuoteRequestDtos.*;

@Component
public class QuoteRequestCanonicalizer {
    private final Validator validator;
    private final ObjectMapper mapper;
    public QuoteRequestCanonicalizer(Validator validator, ObjectMapper mapper) {
        this.validator = validator;
        this.mapper = mapper;
    }

    public Canonical canonicalize(String key, Submission input) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new QuoteRequestFailure(400, "Idempotency-Key debe ser un UUID válido");
        }
        if (input == null || !validator.validate(input).isEmpty()) throw QuoteRequestFailure.invalid();
        var contact = input.contact();
        String phone = contact.phone().replaceAll("[ ()-]", "");
        if (!phone.matches("\\+?[0-9]{7,15}")) throw QuoteRequestFailure.invalid();
        var normalized = new Submission(new Contact(contact.name().strip(), phone, optional(contact.email())),
                optional(input.notes()), input.items().stream().map(item -> new Item(
                    item.productId(), item.saleType(), item.packSize(), item.quantity(), decimal(item.widthMeters()), decimal(item.heightMeters()),
                    item.materialId(), item.extraIds() == null ? List.of() : item.extraIds().stream().distinct().sorted().toList(),
                    optional(item.notes()))).toList());
        // Fixed positional representation: independent of JSON property/map ordering.
        // Item order matters; extra order and decimal scale do not. Version this contract before changing it.
        var canonical = Arrays.asList(1, normalized.contact().name(), phone, normalized.contact().email(),
                normalized.notes(), normalized.items().stream().map(item -> Arrays.asList(
                    item.productId(), item.saleType().name(), item.packSize(), item.quantity(), number(item.widthMeters()), number(item.heightMeters()),
                    item.materialId(), item.extraIds(), item.notes())).toList());
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
            return new Canonical(UUID.fromString(key), hash, normalized);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static String optional(String text) { return text == null || text.isBlank() ? null : text.strip(); }
    private static BigDecimal decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros(); }
    private static String number(BigDecimal value) { return value == null ? null : value.toPlainString(); }

    public record Canonical(UUID key, String hash, Submission submission) {
        @Override public String toString() { return "Canonical[REDACTED]"; }
    }
}
