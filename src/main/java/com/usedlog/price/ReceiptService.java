package com.usedlog.price;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.common.MonthRange;
import com.usedlog.common.Text;
import com.usedlog.household.HouseholdService;
import com.usedlog.price.PriceDtos.ItemRequest;
import com.usedlog.price.PriceDtos.ReaderStatus;
import com.usedlog.price.PriceDtos.ReceiptAlias;
import com.usedlog.price.PriceDtos.ReceiptDetail;
import com.usedlog.price.PriceDtos.ReceiptLine;
import com.usedlog.price.PriceDtos.ReceiptSummary;
import com.usedlog.price.PriceDtos.SaveReceiptRequest;
import com.usedlog.price.ReceiptReader.Photo;
import com.usedlog.price.ReceiptReader.ReceiptDraft;

@Service
public class ReceiptService {

    static final int MAX_PHOTOS = 4;
    static final long MAX_PHOTO_BYTES = 8L * 1024 * 1024;
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final int KNOWN_NAMES_LIMIT = 300;

    private final ReceiptRepository receipts;
    private final ReceiptItemRepository items;
    private final StoreRepository stores;
    private final HouseholdService households;
    private final PriceService prices;
    private final ReceiptReader reader;
    private final Clock clock;

    public ReceiptService(ReceiptRepository receipts, ReceiptItemRepository items, StoreRepository stores,
                          HouseholdService households, PriceService prices, ReceiptReader reader, Clock clock) {
        this.receipts = receipts;
        this.items = items;
        this.stores = stores;
        this.households = households;
        this.prices = prices;
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * Reads photos into a draft; nothing is saved. Deliberately not one transaction: the call to Claude
     * can take several seconds and shouldn't hold a database connection.
     */
    public ReceiptDraft read(UUID householdId, UUID userId, List<MultipartFile> files) {
        households.requireMember(householdId, userId);
        if (!reader.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Reading receipts from photos isn't set up on the server yet. Enter this receipt by hand, "
                            + "or add ANTHROPIC_API_KEY to the API's settings.");
        }
        List<Photo> photos = validPhotos(files);
        return reader.read(photos, knownItemNames(householdId), prices.storeNames(householdId));
    }

    @Transactional(readOnly = true)
    public ReaderStatus readerStatus(UUID householdId, UUID userId) {
        households.requireMember(householdId, userId);
        return new ReaderStatus(reader.isConfigured());
    }

    /**
     * The name, unit and category each printed receipt line was last saved with. A reader that
     * can't expand abbreviations (on-device text recognition) uses these so a fix is made once.
     */
    @Transactional(readOnly = true)
    public List<ReceiptAlias> aliases(UUID householdId, UUID userId) {
        households.requireMember(householdId, userId);
        Map<String, ReceiptAlias> latest = new LinkedHashMap<>();
        for (ReceiptItem line : items.findTop2000ByHouseholdIdAndReceiptTextIsNotNullOrderByPurchasedAtDesc(householdId)) {
            String key = line.getReceiptText().trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
            latest.putIfAbsent(key, new ReceiptAlias(line.getReceiptText(), line.getItemName(), line.getUnit(),
                    line.getCategory()));
        }
        return new ArrayList<>(latest.values());
    }

    @Transactional
    public ReceiptDetail save(UUID householdId, UUID userId, SaveReceiptRequest request) {
        households.requireMember(householdId, userId);
        Instant now = clock.instant();
        if (request.purchasedAt().isAfter(now.plus(Duration.ofDays(1)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "purchasedAt can't be in the future.");
        }

        Store store = findOrCreateStore(householdId, request.storeName(), now);
        BigDecimal tax = money(request.tax());
        BigDecimal linesTotal = request.items().stream()
                .map(item -> money(item.lineTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = request.total() != null
                ? money(request.total())
                : linesTotal.add(tax == null ? BigDecimal.ZERO : tax);

        Receipt receipt = receipts.save(new Receipt(householdId, store.getId(), request.purchasedAt(),
                money(request.subtotal()), tax, total, userId, now));

        List<ReceiptItem> lines = new ArrayList<>();
        int lineNo = 1;
        for (ItemRequest item : request.items()) {
            String name = Text.clean(item.name());
            lines.add(new ReceiptItem(receipt.getId(), householdId, store.getId(), receipt.getPurchasedAt(), lineNo++,
                    name, Text.itemKey(name), Text.cleanOrNull(item.receiptText()),
                    item.quantity().setScale(3, RoundingMode.HALF_UP), Text.clean(item.unit()),
                    item.unitPrice().setScale(3, RoundingMode.HALF_UP), money(item.lineTotal()),
                    Text.clean(item.category())));
        }
        items.saveAll(lines);

        return detail(receipt, store.getName(), lines);
    }

    @Transactional(readOnly = true)
    public List<ReceiptSummary> list(UUID householdId, UUID userId, MonthRange range) {
        households.requireMember(householdId, userId);
        List<Receipt> found = receipts
                .findByHouseholdIdAndPurchasedAtGreaterThanEqualAndPurchasedAtLessThanOrderByPurchasedAtDesc(
                        householdId, range.from(), range.to());
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> counts = items.findByReceiptIdIn(found.stream().map(Receipt::getId).toList()).stream()
                .collect(Collectors.groupingBy(ReceiptItem::getReceiptId, Collectors.counting()));
        Map<UUID, String> storeNames = prices.storeNamesById(householdId);
        return found.stream()
                .map(r -> new ReceiptSummary(r.getId(), storeNames.getOrDefault(r.getStoreId(), ""), r.getPurchasedAt(),
                        r.getTotal(), counts.getOrDefault(r.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReceiptDetail get(UUID householdId, UUID userId, UUID receiptId) {
        households.requireMember(householdId, userId);
        Receipt receipt = find(householdId, receiptId);
        String storeName = prices.storeNamesById(householdId).getOrDefault(receipt.getStoreId(), "");
        return detail(receipt, storeName, items.findByReceiptIdOrderByLineNoAsc(receipt.getId()));
    }

    @Transactional
    public void delete(UUID householdId, UUID userId, UUID receiptId) {
        households.requireMember(householdId, userId);
        Receipt receipt = find(householdId, receiptId);
        items.deleteAll(items.findByReceiptIdOrderByLineNoAsc(receipt.getId()));
        receipts.delete(receipt);
    }

    // ---------- helpers ----------

    private ReceiptDetail detail(Receipt receipt, String storeName, List<ReceiptItem> lines) {
        List<ReceiptLine> lineResponses = lines.stream()
                .map(line -> new ReceiptLine(line.getId(), line.getItemName(), line.getReceiptText(), line.getQuantity(),
                        line.getUnit(), line.getUnitPrice(), line.getLineTotal(), line.getCategory()))
                .toList();
        return new ReceiptDetail(receipt.getId(), storeName, receipt.getPurchasedAt(), receipt.getSubtotal(),
                receipt.getTax(), receipt.getTotal(), receipt.getCreatedBy(), lineResponses,
                prices.changesSince(receipt, lines));
    }

    private Receipt find(UUID householdId, UUID receiptId) {
        return receipts.findByIdAndHouseholdId(receiptId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found."));
    }

    private Store findOrCreateStore(UUID householdId, String name, Instant now) {
        String cleaned = Text.clean(name);
        String key = Text.itemKey(cleaned);
        return stores.findByHouseholdIdAndNameKey(householdId, key)
                .orElseGet(() -> stores.save(new Store(householdId, cleaned, key, now)));
    }

    /** Item names bought in the last year, newest first, one per item. */
    private List<String> knownItemNames(UUID householdId) {
        List<ReceiptItem> recent = items.findByHouseholdIdAndPurchasedAtGreaterThanEqualOrderByPurchasedAtAscLineNoAsc(
                householdId, clock.instant().minus(Duration.ofDays(365)));
        Map<String, String> byKey = new LinkedHashMap<>();
        for (int i = recent.size() - 1; i >= 0 && byKey.size() < KNOWN_NAMES_LIMIT; i--) {
            byKey.putIfAbsent(recent.get(i).getItemKey(), recent.get(i).getItemName());
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<Photo> validPhotos(List<MultipartFile> files) {
        List<MultipartFile> present = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (present.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add a photo of the receipt.");
        }
        if (present.size() > MAX_PHOTOS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use at most " + MAX_PHOTOS + " photos for one receipt.");
        }
        return present.stream().map(ReceiptService::photo).toList();
    }

    private static Photo photo(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!PHOTO_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Photos must be JPEG, PNG, WebP or GIF.");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ResponseStatusException(HttpStatus.valueOf(413), "Each photo must be under 8 MB.");
        }
        try {
            return new Photo(file.getBytes(), type);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That photo couldn't be read. Try again.");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
