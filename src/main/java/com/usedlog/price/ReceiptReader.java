package com.usedlog.price;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Turns photos of a receipt into a draft the person reviews before saving.
 * The real implementation calls Claude ({@link AnthropicReceiptReader}); tests substitute their own.
 */
public interface ReceiptReader {

    /** False when no API key is configured; the app then offers manual entry only. */
    boolean isConfigured();

    /**
     * @param photos      one receipt, top to bottom (long receipts can take several photos)
     * @param knownItems  item names this household already uses, so the same product gets the same name
     * @param knownStores store names this household already uses
     * @throws org.springframework.web.server.ResponseStatusException with a message fit to show the person
     */
    ReceiptDraft read(List<Photo> photos, List<String> knownItems, List<String> knownStores);

    record Photo(byte[] data, String mediaType) {
    }

    /** What was read off the receipt. Any field can be null when it wasn't printed or legible. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReceiptDraft(String storeName, String storeLocation, String purchaseDate, String purchaseTime,
                        String currency,
                        List<DraftItem> items, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                        String notes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DraftItem(String receiptText, String name, BigDecimal quantity, String unit, BigDecimal unitPrice,
                     BigDecimal lineTotal, String category) {
    }
}
