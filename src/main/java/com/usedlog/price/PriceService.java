package com.usedlog.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.common.MonthRange;
import com.usedlog.common.Text;
import com.usedlog.household.HouseholdService;
import com.usedlog.price.PriceDtos.Amount;
import com.usedlog.price.PriceDtos.ItemHistory;
import com.usedlog.price.PriceDtos.ItemPrice;
import com.usedlog.price.PriceDtos.MonthSpending;
import com.usedlog.price.PriceDtos.PriceChange;
import com.usedlog.price.PriceDtos.PricePoint;
import com.usedlog.price.PriceDtos.PricesOverview;
import com.usedlog.price.PriceDtos.StoreComparison;
import com.usedlog.price.PriceDtos.StorePrice;
import com.usedlog.price.PriceDtos.YearSpending;

/**
 * Price questions answered from receipt lines. An "item" here is a product at one unit:
 * bananas by the lb and bananas by the bunch are compared separately.
 */
@Service
public class PriceService {

    /** A change of at least this many percent since the previous purchase is worth flagging. */
    static final BigDecimal CHANGE_ALERT_PERCENT = new BigDecimal("10");

    private static final Duration SUMMARY_WINDOW = Duration.ofDays(365);
    private static final Duration COMPARE_WINDOW = Duration.ofDays(180);
    private static final Duration RECENT_CHANGES_WINDOW = Duration.ofDays(60);

    private final ReceiptRepository receipts;
    private final ReceiptItemRepository items;
    private final StoreRepository stores;
    private final HouseholdService households;
    private final Clock clock;

    public PriceService(ReceiptRepository receipts, ReceiptItemRepository items, StoreRepository stores,
                        HouseholdService households, Clock clock) {
        this.receipts = receipts;
        this.items = items;
        this.stores = stores;
        this.households = households;
        this.clock = clock;
    }

    // ---------- stores ----------

    @Transactional(readOnly = true)
    public List<String> stores(UUID householdId, UUID userId) {
        households.requireMember(householdId, userId);
        return storeNames(householdId);
    }

    List<String> storeNames(UUID householdId) {
        return stores.findByHouseholdIdOrderByNameAsc(householdId).stream().map(Store::getName).toList();
    }

    Map<UUID, String> storeNamesById(UUID householdId) {
        return stores.findByHouseholdIdOrderByNameAsc(householdId).stream()
                .collect(Collectors.toMap(Store::getId, Store::getName));
    }

    // ---------- overview: every item's latest, lowest and average price, plus recent big changes ----------

    @Transactional(readOnly = true)
    public PricesOverview overview(UUID householdId, UUID userId) {
        households.requireMember(householdId, userId);
        Instant now = clock.instant();
        Map<UUID, String> storeNames = storeNamesById(householdId);
        Collection<List<ReceiptItem>> groups = groupByItem(items
                .findByHouseholdIdAndPurchasedAtGreaterThanEqualOrderByPurchasedAtAscLineNoAsc(
                        householdId, now.minus(SUMMARY_WINDOW)));

        List<ItemPrice> summaries = groups.stream()
                .map(group -> summarize(group, storeNames))
                .sorted(Comparator.comparing(ItemPrice::itemName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Instant recent = now.minus(RECENT_CHANGES_WINDOW);
        List<PriceChange> changes = groups.stream()
                .map(group -> latestChange(group, storeNames))
                .filter(Objects::nonNull)
                .filter(change -> !change.latestAt().isBefore(recent))
                .filter(PriceService::isNotable)
                .sorted(Comparator.comparing(PriceChange::changePercent).reversed())
                .toList();

        return new PricesOverview(changes, summaries);
    }

    // ---------- one item's history ----------

    @Transactional(readOnly = true)
    public ItemHistory history(UUID householdId, UUID userId, String item, String unit) {
        households.requireMember(householdId, userId);
        if (item == null || item.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say which item, e.g. ?item=Eggs, 12 ct.");
        }
        List<ReceiptItem> all = items.findByHouseholdIdAndItemKeyOrderByPurchasedAtAscLineNoAsc(
                householdId, Text.itemKey(item));
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No purchases of that item yet.");
        }
        String chosenUnit = (unit == null || unit.isBlank()) ? mostCommonUnit(all) : Text.clean(unit);
        List<ReceiptItem> lines = all.stream().filter(line -> line.getUnit().equalsIgnoreCase(chosenUnit)).toList();
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No purchases of that item in " + chosenUnit + ".");
        }

        Map<UUID, String> storeNames = storeNamesById(householdId);
        List<PricePoint> points = lines.stream()
                .map(line -> new PricePoint(line.getReceiptId(), line.getPurchasedAt(),
                        storeNames.getOrDefault(line.getStoreId(), ""), line.getQuantity(), line.getUnitPrice(),
                        line.getLineTotal()))
                .toList();
        return new ItemHistory(summarize(lines, storeNames), storePrices(lines, storeNames), points);
    }

    // ---------- store comparison ----------

    @Transactional(readOnly = true)
    public List<StoreComparison> compare(UUID householdId, UUID userId) {
        households.requireMember(householdId, userId);
        Map<UUID, String> storeNames = storeNamesById(householdId);
        return groupByItem(items.findByHouseholdIdAndPurchasedAtGreaterThanEqualOrderByPurchasedAtAscLineNoAsc(
                        householdId, clock.instant().minus(COMPARE_WINDOW)))
                .stream()
                .filter(group -> group.stream().map(ReceiptItem::getStoreId).distinct().count() >= 2)
                .map(group -> {
                    ReceiptItem latest = group.get(group.size() - 1);
                    List<StorePrice> byStore = storePrices(group, storeNames);
                    BigDecimal cheapest = byStore.get(0).latestPrice();
                    BigDecimal dearest = byStore.get(byStore.size() - 1).latestPrice();
                    BigDecimal savings = dearest.signum() == 0 ? BigDecimal.ZERO
                            : dearest.subtract(cheapest).multiply(BigDecimal.valueOf(100))
                                    .divide(dearest, 1, RoundingMode.HALF_UP);
                    return new StoreComparison(latest.getItemKey(), latest.getItemName(), latest.getUnit(),
                            latest.getCategory(), byStore, savings);
                })
                .sorted(Comparator.comparing(StoreComparison::savingsPercent).reversed()
                        .thenComparing(StoreComparison::itemName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---------- spending ----------

    @Transactional(readOnly = true)
    public YearSpending spending(UUID householdId, UUID userId, int year, String timeZone) {
        households.requireMember(householdId, userId);
        if (year < 2000 || year > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year must look like 2026.");
        }
        ZoneId zone = MonthRange.zone(timeZone);
        Instant from = MonthRange.of(YearMonth.of(year, 1), zone).from();
        Instant to = MonthRange.of(YearMonth.of(year, 12), zone).to();

        List<Receipt> inYear = receipts
                .findByHouseholdIdAndPurchasedAtGreaterThanEqualAndPurchasedAtLessThanOrderByPurchasedAtDesc(
                        householdId, from, to);
        Map<UUID, List<ReceiptItem>> linesByReceipt = inYear.isEmpty() ? Map.of()
                : items.findByReceiptIdIn(inYear.stream().map(Receipt::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ReceiptItem::getReceiptId));
        Map<UUID, String> storeNames = storeNamesById(householdId);

        Map<Integer, List<Receipt>> byMonth = inYear.stream()
                .collect(Collectors.groupingBy(r -> r.getPurchasedAt().atZone(zone).getMonthValue()));

        List<MonthSpending> months = new ArrayList<>();
        BigDecimal yearTotal = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            List<Receipt> monthReceipts = byMonth.getOrDefault(month, List.of());
            BigDecimal total = sum(monthReceipts.stream().map(Receipt::getTotal).toList());
            yearTotal = yearTotal.add(total);

            Map<String, BigDecimal> byStore = new LinkedHashMap<>();
            Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
            for (Receipt receipt : monthReceipts) {
                byStore.merge(storeNames.getOrDefault(receipt.getStoreId(), "Unknown store"), receipt.getTotal(),
                        BigDecimal::add);
                for (ReceiptItem line : linesByReceipt.getOrDefault(receipt.getId(), List.of())) {
                    byCategory.merge(line.getCategory(), line.getLineTotal(), BigDecimal::add);
                }
                if (receipt.getTax() != null && receipt.getTax().signum() > 0) {
                    byCategory.merge("Tax", receipt.getTax(), BigDecimal::add);
                }
            }
            months.add(new MonthSpending(YearMonth.of(year, month).toString(), total, monthReceipts.size(),
                    largestFirst(byStore), largestFirst(byCategory)));
        }
        return new YearSpending(year, yearTotal, months);
    }

    // ---------- price changes on one receipt (used by ReceiptService) ----------

    /** For each line on the receipt: did its price move 10% or more since the previous time it was bought? */
    List<PriceChange> changesSince(Receipt receipt, List<ReceiptItem> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> storeNames = storeNamesById(receipt.getHouseholdId());
        Map<String, List<ReceiptItem>> earlier = new LinkedHashMap<>();
        for (ReceiptItem line : items.findByHouseholdIdAndPurchasedAtGreaterThanEqualOrderByPurchasedAtAscLineNoAsc(
                receipt.getHouseholdId(), receipt.getPurchasedAt().minus(Duration.ofDays(400)))) {
            if (line.getPurchasedAt().isBefore(receipt.getPurchasedAt()) && !line.getReceiptId().equals(receipt.getId())) {
                earlier.computeIfAbsent(groupKey(line), k -> new ArrayList<>()).add(line);
            }
        }
        List<PriceChange> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReceiptItem line : lines) {
            String key = groupKey(line);
            List<ReceiptItem> before = earlier.get(key);
            if (!seen.add(key) || before == null) {
                continue;
            }
            PriceChange change = change(before.get(before.size() - 1), line, storeNames);
            if (change != null && isNotable(change)) {
                changes.add(change);
            }
        }
        changes.sort(Comparator.comparing(PriceChange::changePercent).reversed());
        return changes;
    }

    // ---------- shared calculations ----------

    /** Lines grouped by item and unit, each group oldest first (input must be oldest first). */
    private static Collection<List<ReceiptItem>> groupByItem(List<ReceiptItem> lines) {
        Map<String, List<ReceiptItem>> groups = new LinkedHashMap<>();
        for (ReceiptItem line : lines) {
            groups.computeIfAbsent(groupKey(line), k -> new ArrayList<>()).add(line);
        }
        return groups.values();
    }

    private static String groupKey(ReceiptItem line) {
        return line.getItemKey() + "\u0000" + line.getUnit().toLowerCase();
    }

    private static ItemPrice summarize(List<ReceiptItem> group, Map<UUID, String> storeNames) {
        ReceiptItem latest = group.get(group.size() - 1);
        ReceiptItem previous = previousPurchase(group);
        ReceiptItem lowest = group.get(0);
        BigDecimal sum = BigDecimal.ZERO;
        for (ReceiptItem line : group) {
            if (line.getUnitPrice().compareTo(lowest.getUnitPrice()) <= 0) {
                lowest = line; // ties go to the most recent
            }
            sum = sum.add(line.getUnitPrice());
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(group.size()), 3, RoundingMode.HALF_UP);
        int storeCount = (int) group.stream().map(ReceiptItem::getStoreId).distinct().count();
        return new ItemPrice(latest.getItemKey(), latest.getItemName(), latest.getUnit(), latest.getCategory(),
                latest.getUnitPrice(), storeNames.getOrDefault(latest.getStoreId(), ""), latest.getPurchasedAt(),
                previous == null ? null : percentChange(previous.getUnitPrice(), latest.getUnitPrice()),
                lowest.getUnitPrice(), storeNames.getOrDefault(lowest.getStoreId(), ""), lowest.getPurchasedAt(),
                average, group.size(), storeCount);
    }

    private static PriceChange latestChange(List<ReceiptItem> group, Map<UUID, String> storeNames) {
        ReceiptItem previous = previousPurchase(group);
        return previous == null ? null : change(previous, group.get(group.size() - 1), storeNames);
    }

    /** The purchase before the latest one, skipping other lines on the same receipt. */
    private static ReceiptItem previousPurchase(List<ReceiptItem> group) {
        UUID latestReceipt = group.get(group.size() - 1).getReceiptId();
        for (int i = group.size() - 2; i >= 0; i--) {
            if (!group.get(i).getReceiptId().equals(latestReceipt)) {
                return group.get(i);
            }
        }
        return null;
    }

    private static PriceChange change(ReceiptItem previous, ReceiptItem latest, Map<UUID, String> storeNames) {
        BigDecimal percent = percentChange(previous.getUnitPrice(), latest.getUnitPrice());
        if (percent == null) {
            return null;
        }
        return new PriceChange(latest.getItemKey(), latest.getItemName(), latest.getUnit(),
                previous.getUnitPrice(), storeNames.getOrDefault(previous.getStoreId(), ""), previous.getPurchasedAt(),
                latest.getUnitPrice(), storeNames.getOrDefault(latest.getStoreId(), ""), latest.getPurchasedAt(),
                percent);
    }

    /** Cheapest first, by each store's most recent price. */
    private static List<StorePrice> storePrices(List<ReceiptItem> group, Map<UUID, String> storeNames) {
        Map<UUID, List<ReceiptItem>> byStore = new LinkedHashMap<>();
        for (ReceiptItem line : group) {
            byStore.computeIfAbsent(line.getStoreId(), k -> new ArrayList<>()).add(line);
        }
        return byStore.entrySet().stream()
                .map(entry -> {
                    List<ReceiptItem> lines = entry.getValue();
                    ReceiptItem latest = lines.get(lines.size() - 1);
                    BigDecimal lowest = lines.stream().map(ReceiptItem::getUnitPrice).min(BigDecimal::compareTo).orElseThrow();
                    return new StorePrice(storeNames.getOrDefault(entry.getKey(), ""), latest.getUnitPrice(),
                            latest.getPurchasedAt(), lowest, lines.size());
                })
                .sorted(Comparator.comparing(StorePrice::latestPrice))
                .toList();
    }

    private static boolean isNotable(PriceChange change) {
        return change.changePercent().abs().compareTo(CHANGE_ALERT_PERCENT) >= 0;
    }

    static BigDecimal percentChange(BigDecimal previous, BigDecimal latest) {
        if (previous.signum() == 0) {
            return null;
        }
        return latest.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static String mostCommonUnit(List<ReceiptItem> lines) {
        return lines.stream()
                .collect(Collectors.groupingBy(ReceiptItem::getUnit, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<Amount> largestFirst(Map<String, BigDecimal> amounts) {
        return amounts.entrySet().stream()
                .map(entry -> new Amount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Amount::amount).reversed())
                .toList();
    }
}
