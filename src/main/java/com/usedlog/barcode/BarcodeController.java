package com.usedlog.barcode;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.usedlog.barcode.BarcodeDtos.BarcodeLookup;
import com.usedlog.barcode.BarcodeDtos.SaveBarcodeRequest;
import com.usedlog.security.AppUserPrincipal;

/**
 * Product barcodes for one household. {@code format} is the scanner's name for the symbology
 * ("ean_13", "upc_a", "upc_e", "ean_8"); it's optional and only matters for 8-digit codes.
 */
@RestController
@RequestMapping("/api/households/{householdId}/barcodes")
public class BarcodeController {

    private final BarcodeService barcodes;

    public BarcodeController(BarcodeService barcodes) {
        this.barcodes = barcodes;
    }

    /** What a scanned barcode is: the household's item with its prices, or a suggested name for a new one. */
    @GetMapping("/{code}")
    public BarcodeLookup lookup(@AuthenticationPrincipal AppUserPrincipal me,
                                @PathVariable UUID householdId,
                                @PathVariable String code,
                                @RequestParam(required = false) String format) {
        return barcodes.lookup(householdId, me.getId(), code, format);
    }

    /** Names a barcode (or renames it). */
    @PutMapping("/{code}")
    public BarcodeLookup save(@AuthenticationPrincipal AppUserPrincipal me,
                              @PathVariable UUID householdId,
                              @PathVariable String code,
                              @RequestParam(required = false) String format,
                              @Valid @RequestBody SaveBarcodeRequest body) {
        return barcodes.save(householdId, me.getId(), code, format, body.itemName());
    }
}
