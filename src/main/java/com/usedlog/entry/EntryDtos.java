package com.usedlog.entry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for the entry endpoints. */
public final class EntryDtos {

    private EntryDtos() {
    }

    /** {@code usedAt} is optional; when missing the entry is logged as "just now". */
    public record CreateEntryRequest(
            @NotBlank @Size(max = 80) String itemName,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal quantity,
            @NotBlank @Size(max = 20) String unit,
            @NotBlank @Size(max = 30) String category,
            @Size(max = 140) String note,
            Instant usedAt) {
    }

    public record EntryResponse(
            UUID id,
            String itemName,
            BigDecimal quantity,
            String unit,
            String category,
            String note,
            Instant usedAt,
            UUID loggedBy,
            Instant createdAt) {

        static EntryResponse from(UsageEntry e) {
            return new EntryResponse(e.getId(), e.getItemName(), e.getQuantity(), e.getUnit(), e.getCategory(),
                    e.getNote(), e.getUsedAt(), e.getUserId(), e.getCreatedAt());
        }
    }

    /** An item for the "Used again?" one-tap list, with the amount used last time. */
    public record FrequentItem(
            String itemName,
            BigDecimal quantity,
            String unit,
            String category,
            long timesUsed,
            Instant lastUsedAt) {
    }
}
