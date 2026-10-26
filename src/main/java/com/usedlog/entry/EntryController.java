package com.usedlog.entry;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.usedlog.entry.EntryDtos.CreateEntryRequest;
import com.usedlog.entry.EntryDtos.EntryResponse;
import com.usedlog.entry.EntryDtos.FrequentItem;
import com.usedlog.events.HouseholdEvents;
import com.usedlog.household.HouseholdService;
import com.usedlog.security.AppUserPrincipal;

/**
 * Everything under one household. Months are passed as {@code month=2026-10} plus the viewer's
 * time zone ({@code tz=America/New_York}) so "October" means October where the viewer lives.
 */
@RestController
@RequestMapping("/api/households/{householdId}")
public class EntryController {

    private final EntryService entries;
    private final HouseholdService households;
    private final HouseholdEvents events;

    public EntryController(EntryService entries, HouseholdService households, HouseholdEvents events) {
        this.entries = entries;
        this.households = households;
        this.events = events;
    }

    @GetMapping("/entries")
    public List<EntryResponse> list(@AuthenticationPrincipal AppUserPrincipal me,
                                    @PathVariable UUID householdId,
                                    @RequestParam String month,
                                    @RequestParam(required = false) String tz) {
        return entries.list(householdId, me.getId(), MonthRange.of(month, tz));
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse create(@AuthenticationPrincipal AppUserPrincipal me,
                                @PathVariable UUID householdId,
                                @Valid @RequestBody CreateEntryRequest body) {
        return entries.create(householdId, me.getId(), body);
    }

    @DeleteMapping("/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUserPrincipal me,
                       @PathVariable UUID householdId,
                       @PathVariable UUID entryId) {
        entries.delete(householdId, me.getId(), entryId);
    }

    @GetMapping("/totals")
    public List<ItemTotal> totals(@AuthenticationPrincipal AppUserPrincipal me,
                                  @PathVariable UUID householdId,
                                  @RequestParam String month,
                                  @RequestParam(required = false) String tz) {
        return entries.totals(householdId, me.getId(), MonthRange.of(month, tz));
    }

    @GetMapping("/items/frequent")
    public List<FrequentItem> frequent(@AuthenticationPrincipal AppUserPrincipal me,
                                       @PathVariable UUID householdId,
                                       @RequestParam(defaultValue = "8") int limit) {
        return entries.frequent(householdId, me.getId(), limit);
    }

    /** Server-Sent Events stream: emits "entries-changed" whenever anyone in the household adds or removes an entry. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal AppUserPrincipal me,
                             @PathVariable UUID householdId) {
        households.requireMember(householdId, me.getId());
        return events.subscribe(householdId);
    }
}
