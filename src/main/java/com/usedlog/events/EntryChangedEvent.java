package com.usedlog.events;

import java.time.Instant;
import java.util.UUID;

/** Published inside the transaction that adds or removes an entry; delivered to browsers after commit. */
public record EntryChangedEvent(UUID householdId, UUID entryId, Type type, Instant usedAt) {

    public enum Type { CREATED, DELETED }
}
