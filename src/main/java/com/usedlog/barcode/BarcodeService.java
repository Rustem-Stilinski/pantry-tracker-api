package com.usedlog.barcode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.barcode.BarcodeDtos.BarcodeLookup;
import com.usedlog.barcode.BarcodeDtos.ItemBarcode;
import com.usedlog.barcode.BarcodeDtos.Suggestion;
import com.usedlog.common.Text;
import com.usedlog.household.HouseholdService;
import com.usedlog.price.PriceService;

/**
 * Scanning: a barcode the household has named opens that item with its prices; a new one comes back
 * with a suggested name to confirm.
 */
@Service
public class BarcodeService {

    private final BarcodeItemRepository barcodes;
    private final HouseholdService households;
    private final PriceService prices;
    private final ProductLookup productLookup;
    private final Clock clock;

    public BarcodeService(BarcodeItemRepository barcodes, HouseholdService households, PriceService prices,
                          ProductLookup productLookup, Clock clock) {
        this.barcodes = barcodes;
        this.households = households;
        this.prices = prices;
        this.productLookup = productLookup;
        this.clock = clock;
    }

    /**
     * Deliberately not one transaction: for a new barcode the public product lookup can take a few
     * seconds and shouldn't hold a database connection.
     */
    public BarcodeLookup lookup(UUID householdId, UUID userId, String rawCode, String format) {
        households.requireMember(householdId, userId);
        String code = normalize(rawCode, format);
        Optional<BarcodeItem> named = barcodes.findByHouseholdIdAndCode(householdId, code);
        if (named.isPresent()) {
            return known(householdId, code, named.get().getItemName());
        }
        Suggestion suggestion = productLookup.find(code)
                .map(p -> new Suggestion(p.name(), p.brand(), p.size(), p.source()))
                .orElse(null);
        return new BarcodeLookup(code, false, null, suggestion, null);
    }

    /** Names a barcode, or renames it if it was named before. */
    @Transactional
    public BarcodeLookup save(UUID householdId, UUID userId, String rawCode, String format, String itemName) {
        households.requireMember(householdId, userId);
        String code = normalize(rawCode, format);
        String name = Text.clean(itemName);
        String key = Text.itemKey(name);
        Instant now = clock.instant();
        barcodes.findByHouseholdIdAndCode(householdId, code).ifPresentOrElse(
                existing -> existing.rename(name, key, now),
                () -> barcodes.save(new BarcodeItem(householdId, code, name, key, userId, now)));
        return known(householdId, code, name);
    }

    /** The barcodes linked to an item (matched like prices: by name, ignoring case and spacing), oldest first. */
    @Transactional(readOnly = true)
    public List<ItemBarcode> forItem(UUID householdId, UUID userId, String item) {
        households.requireMember(householdId, userId);
        if (item == null || item.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say which item, e.g. ?item=Milk 2%, 1 gal.");
        }
        return barcodes.findByHouseholdIdAndItemKeyOrderByCreatedAtAsc(householdId, Text.itemKey(item)).stream()
                .map(b -> new ItemBarcode(b.getCode(), b.getItemName()))
                .toList();
    }

    /** Unlinks a barcode from its item; scanning it again asks for a name. */
    @Transactional
    public void unlink(UUID householdId, UUID userId, String rawCode) {
        households.requireMember(householdId, userId);
        String code = normalize(rawCode, null);
        BarcodeItem found = barcodes.findByHouseholdIdAndCode(householdId, code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That barcode isn't linked to an item."));
        barcodes.delete(found);
    }

    private BarcodeLookup known(UUID householdId, String code, String itemName) {
        return new BarcodeLookup(code, true, itemName, null, prices.summaryFor(householdId, itemName).orElse(null));
    }

    private static String normalize(String rawCode, String format) {
        String code = Barcodes.normalize(rawCode, format);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That isn't a product barcode number. Check the digits under the bars and try again.");
        }
        return code;
    }
}
