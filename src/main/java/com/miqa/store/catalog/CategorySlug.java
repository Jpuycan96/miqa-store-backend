package com.miqa.store.catalog;

import java.text.Normalizer;
import java.util.Locale;

public final class CategorySlug {
    private CategorySlug() {}

    public static String fromName(String name) {
        String normalized = Normalizer.normalize(name == null ? "" : name.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isEmpty() || normalized.length() > 160)
            throw new IllegalArgumentException("El nombre no permite generar una URL válida de hasta 160 caracteres");
        return normalized;
    }

    public static String forUpdate(String currentName, String currentSlug, String submittedName) {
        String trimmedName = submittedName == null ? "" : submittedName.trim();
        return trimmedName.equals(currentName) ? currentSlug : fromName(trimmedName);
    }
}
