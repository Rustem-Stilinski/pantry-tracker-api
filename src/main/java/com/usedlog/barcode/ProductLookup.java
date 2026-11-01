package com.usedlog.barcode;

import java.util.Optional;

/**
 * Finds what a barcode is in a public product database, to suggest a name the first time a household
 * scans it. The real implementation asks Open Food Facts ({@link OpenFoodFactsLookup}); tests substitute
 * their own. Never throws: no answer, a slow answer or an error all mean "no suggestion".
 */
public interface ProductLookup {

    /** @param code a normalized barcode (see {@link Barcodes#normalize}) */
    Optional<Product> find(String code);

    /**
     * @param name  a name ready to use, such as "Fairlife 2% Milk, 52 fl oz"
     * @param brand the first brand listed, or null
     * @param size  the package size as listed ("52 fl oz"), or null
     */
    record Product(String name, String brand, String size, String source) {
    }
}
