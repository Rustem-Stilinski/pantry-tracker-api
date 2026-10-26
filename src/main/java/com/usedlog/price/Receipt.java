package com.usedlog.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One shopping trip: a store, when, and what it cost. Its lines are {@link ReceiptItem}s. */
@Entity
@Table(name = "receipt")
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(precision = 12, scale = 2)
    private BigDecimal tax;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Receipt() {
    }

    public Receipt(UUID householdId, UUID storeId, Instant purchasedAt, BigDecimal subtotal, BigDecimal tax,
                   BigDecimal total, UUID createdBy, Instant createdAt) {
        this.householdId = householdId;
        this.storeId = storeId;
        this.purchasedAt = purchasedAt;
        this.subtotal = subtotal;
        this.tax = tax;
        this.total = total;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
