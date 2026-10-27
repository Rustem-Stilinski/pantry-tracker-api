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

    /** One item's full history, oldest first. */
    List<ReceiptItem> findByHouseholdIdAndItemKeyOrderByPurchasedAtAscLineNoAsc(UUID householdId, String itemKey);
}
