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
     */
    public record BarcodeLookup(String code, boolean known, String itemName, Suggestion suggestion, ItemPrice prices) {
    }

    /** "Fairlife 2% Milk, 52 fl oz" from Open Food Facts. */
    public record Suggestion(String name, String brand, String size, String source) {
    }

    /** A barcode linked to an item: "0036000291452" is "Fairlife 2% milk". */
    public record ItemBarcode(String code, String itemName) {
    }

    /** Names (or renames) a barcode. Use the name the item has on receipts to see its prices. */
    public record SaveBarcodeRequest(@NotBlank @Size(max = 80) String itemName) {
    }
}
