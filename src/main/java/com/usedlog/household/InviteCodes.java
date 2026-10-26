package com.usedlog.household;

import java.security.SecureRandom;
import java.util.Locale;

/** Short, readable invite codes like "K7QM3XRP" (no 0/O or 1/I to avoid typos). */
final class InviteCodes {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Accepts what people paste: "k7qm-3xrp", " K7QM 3XRP " -> "K7QM3XRP". */
    static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
