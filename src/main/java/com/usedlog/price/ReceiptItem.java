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

/**
 * One line on a receipt: "Bananas, 2.31 lb at $0.59/lb = $1.36".
 * Household, store and purchase time are copied from the receipt so price queries need no joins.
 */
@Entity
@Table(name = "receipt_item")
public class ReceiptItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "item_name", nullable = false, length = 80)
    private String itemName;

    /** Lower-cased name, shared with the used-items log so the same product lines up in both. */
    @Column(name = "item_key", nullable = false, length = 80)
    private String itemKey;

    /** The line as printed, e.g. "GV 2% MLK GAL", when it came from a photo. */
    @Column(name = "receipt_text", length = 120)
    private String receiptText;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 3)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Column(nullable = false, length = 30)
    private String category;

    protected ReceiptItem() {
    }

    public ReceiptItem(UUID receiptId, UUID householdId, UUID storeId, Instant purchasedAt, int lineNo,
                       String itemName, String itemKey, String receiptText, BigDecimal quantity, String unit,
                       BigDecimal unitPrice, BigDecimal lineTotal, String category) {
        this.receiptId = receiptId;
        this.householdId = householdId;
        this.storeId = storeId;
        this.purchasedAt = purchasedAt;
        this.lineNo = lineNo;
        this.itemName = itemName;
        this.itemKey = itemKey;
        this.receiptText = receiptText;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
        this.category = category;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReceiptId() {
        return receiptId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getItemName() {
        return itemName;
    }

    public String getItemKey() {
        return itemKey;
    }

    public String getReceiptText() {
        return receiptText;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getCategory() {
        return category;
    }
}
