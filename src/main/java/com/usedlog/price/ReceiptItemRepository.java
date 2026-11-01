package com.usedlog.price;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptItemRepository extends JpaRepository<ReceiptItem, UUID> {

    List<ReceiptItem> findByReceiptIdOrderByLineNoAsc(UUID receiptId);

    List<ReceiptItem> findByReceiptIdIn(Collection<UUID> receiptIds);

    /** Every line bought since a date, oldest first: the input for price summaries and comparisons. */
    List<ReceiptItem> findByHouseholdIdAndPurchasedAtGreaterThanEqualOrderByPurchasedAtAscLineNoAsc(
            UUID householdId, Instant since);

    /** Lines that carry the printed receipt text, newest first: the source of learned names. */
    List<ReceiptItem> findTop2000ByHouseholdIdAndReceiptTextIsNotNullOrderByPurchasedAtDesc(UUID householdId);

    /** The latest line carrying one of these item codes: a scanned barcode's receipt line. */
    java.util.Optional<ReceiptItem> findFirstByHouseholdIdAndItemCodeInOrderByPurchasedAtDesc(UUID householdId,
                                                                                            Collection<String> itemCodes);

    /** Lines that carry an item code, newest first: the codes printed on receipts. */
    List<ReceiptItem> findByHouseholdIdAndItemCodeIsNotNullOrderByPurchasedAtDesc(UUID householdId);

    /** One item's full history, oldest first. */
    List<ReceiptItem> findByHouseholdIdAndItemKeyOrderByPurchasedAtAscLineNoAsc(UUID householdId, String itemKey);
}
