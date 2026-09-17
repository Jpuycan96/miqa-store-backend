package com.miqa.store;

import com.miqa.store.catalog.CategorySlug;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CategorySlugTest {
    @Test void normalizesAccentsEnyeWhitespaceAndSpecialCharacters() {
        assertThat(CategorySlug.fromName("Impresión gran formato")).isEqualTo("impresion-gran-formato");
        assertThat(CategorySlug.fromName("Letreros Publicitarios")).isEqualTo("letreros-publicitarios");
        assertThat(CategorySlug.fromName("Merchandising")).isEqualTo("merchandising");
        assertThat(CategorySlug.fromName("Imprenta y Papelería")).isEqualTo("imprenta-y-papeleria");
        assertThat(CategorySlug.fromName("Señalética")).isEqualTo("senaletica");
        assertThat(CategorySlug.fromName("Branding e Instalaciones")).isEqualTo("branding-e-instalaciones");
        assertThat(CategorySlug.fromName("  Señalética   Ñandú + Especial!!! ")).isEqualTo("senaletica-nandu-especial");
        assertThat(CategorySlug.fromName("--- Imprenta y Papelería ---")).isEqualTo("imprenta-y-papeleria");
    }

    @Test void rejectsNamesWithoutAUsableSlug() {
        assertThatIllegalArgumentException().isThrownBy(() -> CategorySlug.fromName("!!!"));
    }

    @Test void preservesALegacySlugWhenTheStoredNameDoesNotChange() {
        assertThat(CategorySlug.forUpdate("Imprenta y Papelería", "imprenta-papeleria", "Imprenta y Papelería"))
                .isEqualTo("imprenta-papeleria");
        assertThat(CategorySlug.forUpdate("Imprenta y Papelería", "imprenta-papeleria", "  Imprenta y Papelería  "))
                .isEqualTo("imprenta-papeleria");
        assertThat(CategorySlug.forUpdate("Imprenta y Papelería", "imprenta-papeleria", "Imprenta Corporativa"))
                .isEqualTo("imprenta-corporativa");
    }
}
