package com.usedlog.barcode;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BarcodeItemRepository extends JpaRepository<BarcodeItem, UUID> {

    Optional<BarcodeItem> findByHouseholdIdAndCode(UUID householdId, String code);
}
