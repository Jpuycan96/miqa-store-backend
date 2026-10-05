package com.miqa.store.quote;

import com.miqa.store.catalog.ProductSaleType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.math.BigDecimal;
import java.util.List;
import static com.miqa.store.quote.QuoteRequestDtos.*;
import static com.miqa.store.catalog.ProductSaleType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteCatalogTest {
    private QuoteCatalog.Product product(ProductSaleType type) {
        return new QuoteCatalog.Product("p", "Nombre oficial", "producto", new QuoteSnapshot.Category("c", "Categoría", "categoria"),
                type, type == AREA ? "m²" : "unidad", type == PACK ? 1000 : null, type == PACK ? "millar" : null, 12, 12);
    }
    private Item item(ProductSaleType type) { return new Item("p", type, type == PACK ? 1000 : null, 50L, null, null, null, List.of(), null); }
    private void status(Runnable operation, int expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(QuoteRequestFailure.class, ex -> assertThat(ex.status()).isEqualTo(expected));
    }

    @Test void quantity50WithMinimumAndStep12RemainsExactly50() {
        var snapshot = QuoteCatalog.snapshot(product(QUANTITY), item(QUANTITY), null, List.of());
        assertThat(snapshot.quantity()).isEqualTo(50L);
        assertThat(snapshot.rules().minQuantity()).isEqualTo(12);
        assertThat(snapshot.rules().quantityStep()).isEqualTo(12);
        assertThat(snapshot.productName()).isEqualTo("Nombre oficial");
        assertThat(snapshot.category().name()).isEqualTo("Categoría");
        assertThat(snapshot.schemaVersion()).isEqualTo(1);
    }
    @Test void packSnapshotPreservesPresentationAndDoesNotMultiplyQuantity() {
        var snapshot = QuoteCatalog.snapshot(product(PACK), item(PACK), null, List.of());
        assertThat(snapshot.quantity()).isEqualTo(50L);
        assertThat(snapshot.packSize()).isEqualTo(1000);
        assertThat(snapshot.packLabel()).isEqualTo("millar");
        assertThat(snapshot.unitLabel()).isEqualTo("unidad");
    }
    @Test void areaUsesExactBigDecimalPerPieceAndOfficialOptions() {
        var item = new Item("p", AREA, null, 50L, new BigDecimal("0.123456"), new BigDecimal("2.345678"), "m", List.of("e"), "Nota");
        var material = new QuoteSnapshot.Option("m", "Material oficial");
        var extras = List.of(new QuoteSnapshot.Option("e", "Extra oficial"));
        var snapshot = QuoteCatalog.snapshot(product(AREA), item, material, extras);
        assertThat(snapshot.areaSquareMeters()).isEqualByComparingTo("0.289588023168");
        assertThat(snapshot.quantity()).isEqualTo(50);
        assertThat(snapshot.material()).isEqualTo(material);
        assertThat(snapshot.extras()).isEqualTo(extras);
        assertThat(snapshot.notes()).isEqualTo("Nota");
        assertThat(snapshot.rules().minDimensionMeters()).isEqualByComparingTo("0.01");
    }
    @Test void quantityBelowMinimumIsRejectedWithoutRounding() {
        status(() -> QuoteCatalog.snapshot(product(QUANTITY), new Item("p", QUANTITY, null, 11L, null, null, null, List.of(), null), null, List.of()), 400);
    }
    @Test void dimensionsAreRequiredOnlyForArea() {
        status(() -> QuoteCatalog.snapshot(product(AREA), item(AREA), null, List.of()), 400);
        for (var type : List.of(QUANTITY, PACK)) {
            status(() -> QuoteCatalog.snapshot(product(type), new Item("p", type, type == PACK ? 1000 : null, 50L, BigDecimal.ONE, BigDecimal.ONE, null, List.of(), null), null, List.of()), 400);
        }
    }
    @Test void changedSaleTypeOrPackSizeIsRejectedInsteadOfSubstituted() {
        status(() -> QuoteCatalog.snapshot(product(PACK), item(QUANTITY), null, List.of()), 409);
        status(() -> QuoteCatalog.snapshot(product(PACK), new Item("p", PACK, 500, 50L, null, null, null, List.of(), null), null, List.of()), 409);
        status(() -> QuoteCatalog.snapshot(product(PACK), new Item("p", PACK, null, 50L, null, null, null, List.of(), null), null, List.of()), 400);
    }
    @Test void newLegacySubmissionsCannotBypassErpAuthority() {
        var jdbc = mock(JdbcTemplate.class);
        status(() -> new QuoteCatalog(jdbc).snapshot(item(QUANTITY)), 409);
        verifyNoInteractions(jdbc);
    }
}
