package com.usedlog.barcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Product barcode numbers (EAN-13, UPC-A, EAN-8, UPC-E, GTIN-14): cleaning, check digits and one canonical form.
 *
 * <p>The same product can come back from a scanner as UPC-A ("041303012345"), as EAN-13 with a leading zero
 * ("0041303012345"), or printed small as UPC-E ("04130325"). All of these become the 13-digit form, so one
 * product is one barcode.
 */
public final class Barcodes {

    private Barcodes() {
    }

    /**
     * The canonical form of a scanned or typed code, or null when it isn't a valid product barcode.
     *
     * @param raw    digits as read; spaces and dashes are ignored
     * @param format the scanner's format name ("upc_e", "ean_8"...) when known, else null
     */
    public static String normalize(String raw, String format) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{6,14}")) {
            return null;
        }
        String kind = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);

        switch (digits.length()) {
            case 8 -> {
                // EAN-8 and UPC-E are both eight digits. Trust the scanner's format when it gives one;
                // for a typed code, EAN-8 wins when its check digit works, else try it as UPC-E.
                if (kind.equals("upc_e")) {
                    return upcEToEan13(digits);
                }
                if (hasValidCheckDigit(digits)) {
                    return digits;
                }
                return kind.equals("ean_8") ? null : upcEToEan13(digits);
            }
            case 12 -> {
                return hasValidCheckDigit(digits) ? "0" + digits : null;
            }
            case 13 -> {
                return hasValidCheckDigit(digits) ? digits : null;
            }
            case 14 -> {
                if (!hasValidCheckDigit(digits)) {
                    return null;
                }
                // A GTIN-14 with a 0 indicator is the EAN-13 of the same unit.
                return digits.charAt(0) == '0' ? digits.substring(1) : digits;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The ways a receipt can print this barcode: in full, without its check digit, and for a UPC also
     * without its first and last digit (Safeway prints UPC 0 21130 50504 3 as "2113050504").
     *
     * @param code a normalized barcode (see {@link #normalize})
     */
    public static List<String> receiptForms(String code) {
        List<String> forms = new ArrayList<>();
        forms.add(code);
        forms.add(code.substring(0, code.length() - 1));
        if (code.length() == 13 && code.charAt(0) == '0') {
            String upcA = code.substring(1);
            forms.add(upcA);
            forms.add(upcA.substring(0, 11));
            forms.add(upcA.substring(1, 11));
        }
        return forms.stream().distinct().toList();
    }

    /** GS1 check digit: weights 3, 1, 3... from the right, ignoring the check digit itself. */
    static boolean hasValidCheckDigit(String digits) {
        return digits.charAt(digits.length() - 1) - '0' == checkDigit(digits.substring(0, digits.length() - 1));
    }

    static int checkDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = body.charAt(body.length() - 1 - i) - '0';
            sum += i % 2 == 0 ? digit * 3 : digit;
        }
        return (10 - sum % 10) % 10;
    }

    /**
     * Expands a UPC-E code (number system 0 or 1, six digits, check digit) to its UPC-A and returns that
     * as EAN-13, or null when it isn't a valid UPC-E.
     */
    static String upcEToEan13(String upcE) {
        char system = upcE.charAt(0);
        if (system != '0' && system != '1') {
            return null;
        }
        String d = upcE.substring(1, 7);
        String body = switch (d.charAt(5)) {
            case '0', '1', '2' -> d.substring(0, 2) + d.charAt(5) + "0000" + d.substring(2, 5);
            case '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5);
            case '4' -> d.substring(0, 4) + "00000" + d.charAt(4);
            default -> d.substring(0, 5) + "0000" + d.charAt(5);
        };
        String upcA = system + body + upcE.charAt(7);
        return hasValidCheckDigit(upcA) ? "0" + upcA : null;
    }
}
