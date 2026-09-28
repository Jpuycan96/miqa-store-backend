package com.miqa.store.quote;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import static com.miqa.store.quote.QuoteRequestDtos.*;
import static com.miqa.store.catalog.ProductSaleType.*;
import static org.assertj.core.api.Assertions.*;

class QuoteRequestCanonicalizerTest {
    static ValidatorFactory validation;
    static QuoteRequestCanonicalizer canonicalizer;
    static final JsonMapper mapper = JsonMapper.builder().build();
    static final String KEY = "3c028b25-d423-4b39-a36b-b13621fdd745";
    @BeforeAll static void setup() {
        validation = Validation.buildDefaultValidatorFactory();
        canonicalizer = new QuoteRequestCanonicalizer(validation.getValidator(), mapper);
    }
    @AfterAll static void close() { validation.close(); }

    static Item quantity(long count) { return new Item("product", QUANTITY, null, count, null, null, null, List.of(), null); }
    static Submission submission(Item... items) { return new Submission(new Contact("Cliente", "+51999999999", null), null, List.of(items)); }
    private String hash(Submission input) { return canonicalizer.canonicalize(KEY, input).hash(); }
    private void invalid(Submission input) {
        assertThatThrownBy(() -> hash(input)).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(400));
    }

    @Test void stableHashIgnoresFormattingExtraOrderDuplicatesAndDecimalScale() {
        var first = new Item("vinil", AREA, null, 50L, new BigDecimal("2.50"), new BigDecimal("1.20"), "white", List.of("b", "a", "a"), " Nota ");
        var second = new Item("vinil", AREA, null, 50L, new BigDecimal("2.5"), new BigDecimal("1.2"), "white", List.of("a", "b"), "Nota");
        var input = new Submission(new Contact(" Cliente ", "+51 (999) 999-999", ""), "  ", List.of(first));
        assertThat(hash(input)).isEqualTo(hash(submission(second))).matches("[0-9a-f]{64}");
        assertThat(canonicalizer.canonicalize(KEY.toUpperCase(Locale.ROOT), input).key()).isEqualTo(UUID.fromString(KEY));
        var normalized = canonicalizer.canonicalize(KEY, input).submission();
        assertThat(normalized.items().getFirst().quantity()).isEqualTo(50L);
        assertThat(normalized.contact().phone()).isEqualTo("+51999999999");
    }

    @Test void contentAndItemOrderMatterButKeyDoesNotChangeHash() {
        var first = submission(quantity(12), quantity(50));
        assertThat(hash(first)).isNotEqualTo(hash(submission(quantity(50), quantity(12))));
        assertThat(hash(first)).isNotEqualTo(hash(submission(quantity(12), quantity(51))));
        assertThat(hash(first)).isEqualTo(canonicalizer.canonicalize(UUID.randomUUID().toString(), first).hash());
        assertThat(hash(first)).isNotEqualTo(hash(new Submission(new Contact("Otro", "+51999999999", null), null, first.items())));
        assertThat(hash(first)).isNotEqualTo(hash(new Submission(first.contact(), "Otra nota", first.items())));
    }

    @Test void missingMalformedAndOverlongKeysAreRejected() {
        for (String key : Arrays.asList(null, "", "1-1-1-1-1", "invalid", KEY + "x", " " + KEY)) {
            assertThatThrownBy(() -> canonicalizer.canonicalize(key, submission(quantity(1)))).isInstanceOf(QuoteRequestFailure.class);
        }
    }

    @Test void contactIsRequiredAndBounded() {
        invalid(new Submission(null, null, List.of(quantity(1))));
        for (Contact contact : List.of(new Contact("", "999999999", null), new Contact("x".repeat(161), "999999999", null),
                new Contact("Cliente", "123", null), new Contact("Cliente", "9".repeat(16), null),
                new Contact("Cliente", "phone", null), new Contact("Cliente", "999999999", "bad-email"),
                new Contact("Cliente", "999999999", "a".repeat(255) + "@example.test"))) {
            invalid(new Submission(contact, null, List.of(quantity(1))));
        }
        assertThat(hash(submission(quantity(1)))).isNotBlank();
    }

    @Test void itemCountAndNotesHaveExplicitLimits() {
        var contact = submission(quantity(1)).contact();
        invalid(new Submission(contact, null, List.of()));
        invalid(new Submission(contact, null, Collections.nCopies(51, quantity(1))));
        invalid(new Submission(contact, "x".repeat(1001), List.of(quantity(1))));
        invalid(submission(new Item("p", QUANTITY, null, 1L, null, null, null, List.of(), "x".repeat(1001))));
        assertThat(hash(new Submission(contact, "x".repeat(1000), Collections.nCopies(50, quantity(1))))).isNotBlank();
    }

    @Test void quantityMustBePositiveAndBelowInteroperableMaximum() {
        invalid(submission(quantity(0)));
        invalid(submission(quantity(-1)));
        invalid(submission(quantity(MAX_QUANTITY + 1)));
        invalid(submission(quantity(9_007_199_254_740_992L)));
        assertThat(hash(submission(quantity(MAX_QUANTITY)))).isNotBlank();
    }

    @Test void dimensionsMustMeetActualFrontendMinimumAndPrecisionLimits() {
        for (String width : List.of("0", "-1", "0.009", "1000.01", "1.1234567")) {
            invalid(submission(new Item("p", AREA, null, 1L, new BigDecimal(width), BigDecimal.ONE, null, List.of(), null)));
        }
        assertThat(hash(submission(new Item("p", AREA, null, 1L, new BigDecimal("0.01"), new BigDecimal("1000"), null, List.of(), null)))).isNotBlank();
    }

    @Test void nestedNullsAndOversizedOptionIdsFailValidation() {
        invalid(new Submission(new Contact("Cliente", "999999999", null), null, Arrays.asList((Item) null)));
        invalid(submission(new Item("p", QUANTITY, null, null, null, null, null, List.of(), null)));
        invalid(submission(new Item("p", QUANTITY, null, 1L, null, null, null, Arrays.asList((String) null), null)));
        invalid(submission(new Item("p", QUANTITY, null, 1L, null, null, "x".repeat(65), List.of(), null)));
        invalid(submission(new Item("p", QUANTITY, null, 1L, null, null, null, Collections.nCopies(51, "e"), null)));
    }

    @Test void jsonQuantityRejectsFractionStringBooleanAndLongOverflow() {
        for (String value : List.of("12.9", "\"12\"", "true", "9223372036854775808")) {
            assertThatThrownBy(() -> mapper.readValue("{\"productId\":\"p\",\"saleType\":\"QUANTITY\",\"quantity\":" + value + "}", Item.class))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(mapper.readValue("{\"productId\":\"p\",\"saleType\":\"QUANTITY\",\"quantity\":50}", Item.class).quantity()).isEqualTo(50L);
    }

    @Test void browserOwnedNamesAreaStateAndPriceAreNotPartOfSubmissionOrHash() {
        String json = """
                {"contact":{"name":"Cliente","phone":"+51999999999"},"reference":"fake","status":"DONE","origin":"ERP",
                 "items":[{"productId":"product","saleType":"QUANTITY","quantity":50,"productName":"fake","area":999,"price":0}]}
                """;
        assertThat(hash(mapper.readValue(json, Submission.class))).isEqualTo(hash(submission(quantity(50))));
    }

    @Test void diagnosticStringsNeverContainInputOrKey() {
        var input = new Submission(new Contact("private name", "999999999", "private@example.test"), "private note", List.of(quantity(1)));
        var canonical = canonicalizer.canonicalize(KEY, input);
        assertThat(input.toString() + input.contact() + input.items() + canonical)
                .doesNotContain("private", "999999999", KEY, canonical.hash());
    }
}
