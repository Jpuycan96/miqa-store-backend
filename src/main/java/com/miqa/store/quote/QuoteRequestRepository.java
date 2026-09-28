package com.miqa.store.quote;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Repository
public class QuoteRequestRepository {
    static final String IDEMPOTENCY_CONSTRAINT = "uq_quote_requests_idempotency_key";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public QuoteRequestRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Optional<Receipt> find(UUID key) {
        return jdbc.query("SELECT reference, created_at, request_hash FROM quote_requests WHERE idempotency_key = ?",
                (rs, row) -> new Receipt(rs.getString("reference"), rs.getTimestamp("created_at").toInstant(),
                    rs.getString("request_hash")), key).stream().findFirst();
    }

    public Receipt insert(QuoteRequestCanonicalizer.Canonical canonical, List<QuoteSnapshot> items) {
        long number = Objects.requireNonNull(jdbc.queryForObject("SELECT nextval('quote_request_reference_seq')", Long.class));
        String reference = reference(number);
        String id = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var input = canonical.submission();
        jdbc.update("""
                INSERT INTO quote_requests (id, reference_number, reference, status, origin,
                    contact_name, contact_phone, contact_email, notes, idempotency_key, request_hash, created_at, updated_at)
                VALUES (?, ?, ?, 'RECIBIDA', 'TIENDA_VIRTUAL', ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, number, reference, input.contact().name(), input.contact().phone(), input.contact().email(),
                input.notes(), canonical.key(), canonical.hash(), Timestamp.from(receivedAt), Timestamp.from(receivedAt));
        int position = 0;
        for (QuoteSnapshot item : items) {
            jdbc.update("""
                    INSERT INTO quote_request_items (id, request_id, position, product_id, product_name, product_slug,
                        sale_type, quantity, unit_label, pack_size, pack_label, width_meters, height_meters,
                        area_square_meters, notes, snapshot, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """, UUID.randomUUID().toString(), id, ++position, item.productId(), item.productName(), item.productSlug(),
                    item.saleType().name(), item.quantity(), item.unitLabel(), item.packSize(), item.packLabel(), item.widthMeters(),
                    item.heightMeters(), item.areaSquareMeters(), item.notes(), mapper.writeValueAsString(item),
                    Timestamp.from(receivedAt), Timestamp.from(receivedAt));
        }
        return new Receipt(reference, receivedAt, canonical.hash());
    }

    static String reference(long number) { return "MIQA-" + String.format(Locale.ROOT, "%06d", number); }

    public record Receipt(String reference, Instant receivedAt, String hash) {
        public QuoteRequestDtos.Confirmation confirmation() {
            return new QuoteRequestDtos.Confirmation(reference, receivedAt, "Solicitud recibida");
        }
        @Override public String toString() { return "Receipt[REDACTED]"; }
    }
}
