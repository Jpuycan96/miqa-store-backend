package com.miqa.store.quote;

import com.miqa.store.catalog.ProductSaleType;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.*;
import java.util.*;

@Service
public class QuoteV2Service {
    private final QuoteRequestRepository repository;
    private final QuoteRequestCanonicalizer legacy;
    private final QuoteCatalog catalog;
    private final ErpQuoteSelection erp;
    private final com.miqa.store.pricing.ErpPricing pricing;
    private final Validator validator;
    private final ObjectMapper mapper;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    @Autowired
    public QuoteV2Service(QuoteRequestRepository repository, QuoteRequestCanonicalizer legacy, QuoteCatalog catalog,
            ErpQuoteSelection erp, com.miqa.store.pricing.ErpPricing pricing, Validator validator, ObjectMapper mapper, DataSource dataSource) {
        this(repository, legacy, catalog, erp, pricing, validator, mapper, new JdbcTransactionManager(dataSource));
    }
    QuoteV2Service(QuoteRequestRepository repository, QuoteRequestCanonicalizer legacy, QuoteCatalog catalog,
            ErpQuoteSelection erp, com.miqa.store.pricing.ErpPricing pricing, Validator validator, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.pricing=pricing; this.repository=repository; this.legacy=legacy; this.catalog=catalog; this.erp=erp; this.validator=validator; this.mapper=mapper;
        write = new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write.setTimeout(15);
        read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(10);
    }
    public QuoteRequestDtos.Result submit(String key, QuoteV2Dtos.Submission input) {
        var normalized = normalize(key, input);
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(normalized))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
        var canonical = new QuoteRequestCanonicalizer.Canonical(UUID.fromString(key), hash,
                new QuoteRequestDtos.Submission(normalized.contact(), normalized.notes(), List.of()));
        var previous = read.execute(tx -> repository.find(canonical.key()));
        if (previous != null && previous.isPresent()) return replay(previous.get(), hash);
        // Local snapshots are detached values; the read transaction ends before any HTTP call.
        var prepared = Objects.requireNonNull(read.execute(tx -> snapshots(normalized)));
        var evaluated = prepared.stream().map(item -> {
            if (!(item.snapshot() instanceof QuoteV2Dtos.ErpSnapshot snapshot)) return item;
            var result = pricing.evaluate(snapshot);
            if (result.status()!=com.miqa.store.pricing.PricingDtos.Status.PRICE_AVAILABLE
                    && result.status()!=com.miqa.store.pricing.PricingDtos.Status.QUOTE_REQUIRED)
                throw new com.miqa.store.pricing.PricingFailure(result.status());
            return new QuoteV2Dtos.StoredItem(item.productId(),snapshot.withPricing(result));
        }).toList();
        try {
            return Objects.requireNonNull(write.execute(tx -> {
                var existing = repository.find(canonical.key());
                if (existing.isPresent()) return replay(existing.get(), hash);
                // Detect publication/binding/projection changes while HTTP was in flight.
                if (!prepared.equals(snapshots(normalized))) throw QuoteRequestFailure.catalogChanged();
                return new QuoteRequestDtos.Result(repository.insertV2(canonical, evaluated).confirmation(), false);
            }));
        } catch (DataIntegrityViolationException ex) {
            if (!QuoteRequestService.isIdempotencyCollision(ex)) throw ex;
            var winner = read.execute(tx -> repository.find(canonical.key()));
            if (winner == null || winner.isEmpty()) throw ex;
            return replay(winner.get(), hash);
        }
    }
    private List<QuoteV2Dtos.StoredItem> snapshots(QuoteV2Dtos.Submission normalized) {
        return normalized.items().stream().map(item -> new QuoteV2Dtos.StoredItem(item.productId(),
                item.erp() == null ? catalog.snapshot(legacyItem(item)) : erp.snapshot(item))).toList();
    }
    QuoteV2Dtos.Submission normalize(String key, QuoteV2Dtos.Submission input) {
        if (input == null || !validator.validate(input).isEmpty()) throw QuoteRequestFailure.invalid();
        var contact = legacy.canonicalize(key, new QuoteRequestDtos.Submission(input.contact(), input.notes(), List.of(
                new QuoteRequestDtos.Item("validation", ProductSaleType.QUANTITY, null, 1L, null, null, null, List.of(), null)))).submission();
        var items = input.items().stream().map(item -> {
            if (item.erp() == null) {
                var normalized = legacy.canonicalize(key, new QuoteRequestDtos.Submission(input.contact(), input.notes(),
                        List.of(legacyItem(item)))).submission().items().getFirst();
                return new QuoteV2Dtos.Item(normalized.productId(), normalized.saleType(), normalized.packSize(),
                        BigDecimal.valueOf(normalized.quantity()), normalized.widthMeters(), normalized.heightMeters(),
                        normalized.materialId(), normalized.extraIds(), normalized.notes(), null);
            }
            if (item.saleType() != null || item.packSize() != null || item.widthMeters() != null || item.heightMeters() != null
                    || item.materialId() != null || item.extraIds() != null && !item.extraIds().isEmpty()) throw QuoteRequestFailure.invalid();
            var selection = item.erp();
            var measures = new TreeMap<String, BigDecimal>();
            selection.measures().forEach((name, value) -> measures.put(name, value.stripTrailingZeros()));
            return new QuoteV2Dtos.Item(item.productId(), null, null, item.quantity().stripTrailingZeros(), null, null, null,
                    List.of(), optional(item.notes()), new QuoteV2Dtos.Selection(selection.erpServiceId(), selection.catalogRevision(),
                    selection.configurationVersion(), selection.erpMaterialId(), selection.erpModelId(), measures));
        }).toList();
        return new QuoteV2Dtos.Submission(2, contact.contact(), contact.notes(), items);
    }
    private static QuoteRequestDtos.Item legacyItem(QuoteV2Dtos.Item item) {
        try { return new QuoteRequestDtos.Item(item.productId(), item.saleType(), item.packSize(), item.quantity().longValueExact(),
                item.widthMeters(), item.heightMeters(), item.materialId(), item.extraIds(), item.notes()); }
        catch (ArithmeticException ex) { throw QuoteRequestFailure.invalid(); }
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static QuoteRequestDtos.Result replay(QuoteRequestRepository.Receipt receipt, String hash) {
        if (!receipt.hash().equals(hash)) throw new QuoteRequestFailure(409, "Idempotency-Key ya fue usada con otro contenido");
        return new QuoteRequestDtos.Result(receipt.confirmation(), true);
    }
}
