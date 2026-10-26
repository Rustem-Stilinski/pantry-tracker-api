package com.usedlog.entry;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of a month's totals: "Paper towels, 7 rolls, logged 6 times, last used today".
 * Top-level (not nested) so the JPQL constructor expression in {@link UsageEntryRepository} can name it.
 */
public record ItemTotal(
        String itemKey,
        String itemName,
        String unit,
        String category,
        BigDecimal quantity,
        Long times,
        Instant lastUsedAt) {
}
