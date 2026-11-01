package com.usedlog.barcode;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A barcode this household has named: scanning it again opens that item. */
@Entity
@Table(name = "barcode_item")
public class BarcodeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 14)
    private String code;

    @Column(name = "item_name", nullable = false, length = 80)
    private String itemName;

    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BarcodeItem() {
    }

    public BarcodeItem(UUID householdId, String code, String itemName, String itemKey, UUID createdBy, Instant now) {
        this.householdId = householdId;
        this.code = code;
        this.itemName = itemName;
        this.itemKey = itemKey;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String itemName, String itemKey, Instant now) {
        this.itemName = itemName;
        this.itemKey = itemKey;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getCode() {
        return code;
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemKey() {
        return itemKey;
    }
}
