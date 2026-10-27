package com.usedlog.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for receipts, prices and spending. Prices are per unit (per lb, per each...). */
public final class PriceDtos {

    private PriceDtos() {
    }

    // ---------- saving a receipt ----------

    /**
     * {@code total} is optional; when missing it's the sum of the lines plus tax. {@code storeLocation} is the
     * branch as printed ("11620 Monument Dr, Fairfax, VA 22030"), optional.
     */
    public record SaveReceiptRequest(
            @NotBlank @Size(max = 80) String storeName,
            @Size(max = 160) String storeLocation,
            @NotNull Instant purchasedAt,
            @DecimalMin("0") BigDecimal subtotal,
            @DecimalMin("0") BigDecimal tax,
            @DecimalMin("0") BigDecimal total,
            @NotEmpty @Size(max = 300) List<@Valid ItemRequest> items) {
    }

    public record ItemRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 120) String receiptText,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            @NotBlank @Size(max = 20) String unit,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @NotNull @DecimalMin("0") BigDecimal lineTotal,
            @NotBlank @Size(max = 30) String category) {
    }

    // ---------- receipts ----------

    public record ReceiptSummary(UUID id, String storeName, String storeLocation, Instant purchasedAt, BigDecimal total,
                                 int itemCount) {
    }

    public record ReceiptLine(UUID id, String name, String receiptText, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, BigDecimal lineTotal, String category) {
    }

    /** {@code priceChanges}: lines whose price moved 10% or more since the previous time they were bought. */
    public record ReceiptDetail(UUID id, String storeName, String storeLocation, Instant purchasedAt,
                                BigDecimal subtotal, BigDecimal tax,
                                BigDecimal total, UUID createdBy, List<ReceiptLine> items,
                                List<PriceChange> priceChanges) {
    }

    /** Which reader the server offers. {@code claude} is false when no API key is set. */
    public record ReaderStatus(boolean claude) {
    }

    /** "GV 2% MLK GAL" was last saved as "Milk 2%, 1 gal" (pcs, Dairy & eggs). */
    public record ReceiptAlias(String receiptText, String name, String unit, String category) {
    }

    // ---------- prices ----------

    /** "Eggs, 12 ct went from $2.79 at Aldi to $3.49 at Giant (+25.1%)". */
    public record PriceChange(String itemKey, String itemName, String unit,
                              BigDecimal previousPrice, String previousStore, Instant previousAt,
                              BigDecimal latestPrice, String latestStore, Instant latestAt,
                              BigDecimal changePercent) {
    }

    /** One item at one unit, over the last year. {@code changePercent} is latest vs the purchase before it. */
    public record ItemPrice(String itemKey, String itemName, String unit, String category,
                            BigDecimal latestPrice, String latestStore, Instant latestAt, BigDecimal changePercent,
                            BigDecimal lowestPrice, String lowestStore, Instant lowestAt,
                            BigDecimal averagePrice, int purchases, int storeCount) {
    }

    public record PricesOverview(List<PriceChange> changes, List<ItemPrice> items) {
    }

    public record PricePoint(UUID receiptId, Instant purchasedAt, String storeName, String storeLocation,
                             BigDecimal quantity,
                             BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record StorePrice(String storeName, BigDecimal latestPrice, Instant latestAt, BigDecimal lowestPrice,
                             int purchases) {
    }

    /** An item's whole history: summary, the price at each store, and every purchase (oldest first). */
    public record ItemHistory(ItemPrice summary, List<StorePrice> stores, List<PricePoint> points) {
    }

    /** An item bought at two or more stores. Stores are cheapest first by their latest price. */
    public record StoreComparison(String itemKey, String itemName, String unit, String category,
                                  List<StorePrice> stores, BigDecimal savingsPercent) {
    }

    // ---------- spending ----------

    public record Amount(String name, BigDecimal amount) {
    }

    public record MonthSpending(String month, BigDecimal total, int receipts, List<Amount> byStore,
                                List<Amount> byCategory) {
    }

    public record YearSpending(int year, BigDecimal total, List<MonthSpending> months) {
    }
}
