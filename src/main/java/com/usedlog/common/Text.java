package com.usedlog.common;

import java.util.Locale;

/** Small helpers for cleaning user-typed text. */
public final class Text {

    private Text() {
    }

    /** Trims and collapses inner whitespace: "  paper   towels " -> "paper towels". */
    public static String clean(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ");
    }

    /** Cleans and returns null for empty input. */
    public static String cleanOrNull(String value) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    /** Case-insensitive key used to group the same item typed differently ("Milk" / "milk "). */
    public static String itemKey(String itemName) {
        return clean(itemName).toLowerCase(Locale.ROOT);
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
