package com.usedlog.barcode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.usedlog.price.PriceDtos.ItemPrice;

/** Request and response bodies for scanning barcodes. */
public final class BarcodeDtos {

    private BarcodeDtos() {
    }

    /**
     * What a scanned barcode is for this household.
     *
     * @param code       the barcode in its canonical form; use this one when saving
     * @param known      true when this household has named the barcode
     * @param itemName   the item's name when known, else null
     * @param suggestion a name from a public product database when the barcode isn't known yet, else null
     * @param prices     the item's prices from receipts (latest, lowest, average) when there are any, else null
     * @param receiptCode the code printed on a receipt that just linked this barcode ("2113050504"), else null
     */
    public record BarcodeLookup(String code, boolean known, String itemName, Suggestion suggestion, ItemPrice prices,
                                String receiptCode) {
    }

    /** "Fairlife 2% Milk, 52 fl oz" from Open Food Facts. */
    public record Suggestion(String name, String brand, String size, String source) {
    }

    /**
     * A code linked to an item. {@code source} "scan": a barcode someone scanned or typed ("0036000291452");
     * "receipt": the code printed on the item's receipt lines, maybe without the UPC's end digits ("2113050504").
     */
    public record ItemBarcode(String code, String itemName, String source) {
    }

    /** Names (or renames) a barcode. Use the name the item has on receipts to see its prices. */
    public record SaveBarcodeRequest(@NotBlank @Size(max = 80) String itemName) {
    }
}
