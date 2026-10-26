package com.usedlog.price;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    Optional<Store> findByHouseholdIdAndNameKey(UUID householdId, String nameKey);

    List<Store> findByHouseholdIdOrderByNameAsc(UUID householdId);
}
