package com.usedlog.price;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByIdAndHouseholdId(UUID id, UUID householdId);

    List<Receipt> findByHouseholdIdAndPurchasedAtGreaterThanEqualAndPurchasedAtLessThanOrderByPurchasedAtDesc(
            UUID householdId, Instant from, Instant to);
}
