package com.usedlog.entry;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.common.Text;
import com.usedlog.entry.EntryDtos.CreateEntryRequest;
import com.usedlog.entry.EntryDtos.EntryResponse;
import com.usedlog.entry.EntryDtos.FrequentItem;
import com.usedlog.events.EntryChangedEvent;
import com.usedlog.household.HouseholdService;

@Service
public class EntryService {

    /** How far back the "Used again?" list looks. */
    private static final Duration FREQUENT_WINDOW = Duration.ofDays(60);

    private final UsageEntryRepository entries;
    private final HouseholdService households;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EntryService(UsageEntryRepository entries, HouseholdService households,
                        ApplicationEventPublisher events, Clock clock) {
        this.entries = entries;
        this.households = households;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EntryResponse> list(UUID householdId, UUID userId, MonthRange range) {
        households.requireMember(householdId, userId);
        return entries.findByHouseholdIdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDesc(
                        householdId, range.from(), range.to())
                .stream()
                .map(EntryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ItemTotal> totals(UUID householdId, UUID userId, MonthRange range) {
        households.requireMember(householdId, userId);
        return entries.totals(householdId, range.from(), range.to());
    }

    @Transactional
    public EntryResponse create(UUID householdId, UUID userId, CreateEntryRequest request) {
        households.requireMember(householdId, userId);

        Instant now = clock.instant();
        Instant usedAt = request.usedAt() == null ? now : request.usedAt();
        if (usedAt.isAfter(now.plus(Duration.ofMinutes(1)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "usedAt can't be in the future.");
        }

        String itemName = Text.clean(request.itemName());
        UsageEntry entry = new UsageEntry(
                householdId,
                userId,
                itemName,
                Text.itemKey(itemName),
                request.quantity().setScale(2, RoundingMode.HALF_UP),
                Text.clean(request.unit()),
                Text.clean(request.category()),
                Text.cleanOrNull(request.note()),
                usedAt,
                now);
        entries.save(entry);

        events.publishEvent(new EntryChangedEvent(householdId, entry.getId(), EntryChangedEvent.Type.CREATED, usedAt));
        return EntryResponse.from(entry);
    }

    @Transactional
    public void delete(UUID householdId, UUID userId, UUID entryId) {
        households.requireMember(householdId, userId);
        UsageEntry entry = entries.findByIdAndHouseholdId(entryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found."));
        entries.delete(entry);
        events.publishEvent(new EntryChangedEvent(householdId, entryId, EntryChangedEvent.Type.DELETED, entry.getUsedAt()));
    }

    /**
     * Items logged most often in the last 60 days, each with the amount, unit and category used
     * the last time, so the UI can offer "+1 roll" style one-tap repeats.
     */
    @Transactional(readOnly = true)
    public List<FrequentItem> frequent(UUID householdId, UUID userId, int limit) {
        households.requireMember(householdId, userId);
        int capped = Math.max(1, Math.min(limit, 20));
        Instant since = clock.instant().minus(FREQUENT_WINDOW);

        // Newest first, so the first entry seen for each item is the most recent one.
        Map<String, UsageEntry> latest = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (UsageEntry e : entries.findTop500ByHouseholdIdAndUsedAtGreaterThanEqualOrderByUsedAtDesc(householdId, since)) {
            latest.putIfAbsent(e.getItemKey(), e);
            counts.merge(e.getItemKey(), 1L, Long::sum);
        }

        List<FrequentItem> items = new ArrayList<>();
        latest.forEach((key, e) -> items.add(new FrequentItem(
                e.getItemName(), e.getQuantity(), e.getUnit(), e.getCategory(), counts.get(key), e.getUsedAt())));
        items.sort(Comparator.comparingLong(FrequentItem::timesUsed).reversed()
                .thenComparing(FrequentItem::lastUsedAt, Comparator.reverseOrder()));
        return items.subList(0, Math.min(capped, items.size()));
    }
}
