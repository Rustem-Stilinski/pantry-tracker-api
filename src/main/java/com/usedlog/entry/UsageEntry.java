package com.usedlog.entry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One "we used this" record: 2 rolls of paper towels, used Oct 25 at 6:05 PM, logged by Sam. */
@Entity
@Table(name = "usage_entry")
public class UsageEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    /** Null if the person's account was deleted. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "item_name", nullable = false, length = 80)
    private String itemName;

    /** Lower-cased item name, used to group "Milk" and "milk". */
    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(length = 140)
    private String note;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageEntry() {
    }

    public UsageEntry(UUID householdId, UUID userId, String itemName, String itemKey, BigDecimal quantity,
                      String unit, String category, String note, Instant usedAt, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.itemName = itemName;
        this.itemKey = itemKey;
        this.quantity = quantity;
        this.unit = unit;
        this.category = category;
        this.note = note;
        this.usedAt = usedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemKey() {
        return itemKey;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public String getCategory() {
        return category;
    }

    public String getNote() {
        return note;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
