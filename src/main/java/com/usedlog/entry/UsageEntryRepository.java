package com.usedlog.entry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageEntryRepository extends JpaRepository<UsageEntry, UUID> {

    List<UsageEntry> findByHouseholdIdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDesc(
            UUID householdId, Instant from, Instant to);

    Optional<UsageEntry> findByIdAndHouseholdId(UUID id, UUID householdId);

    /** Recent history for the one-tap list; capped so it stays cheap for busy households. */
    List<UsageEntry> findTop500ByHouseholdIdAndUsedAtGreaterThanEqualOrderByUsedAtDesc(UUID householdId, Instant since);

    @Query("""
            select new com.usedlog.entry.ItemTotal(
                e.itemKey, max(e.itemName), e.unit, max(e.category), sum(e.quantity), count(e), max(e.usedAt))
            from UsageEntry e
            where e.householdId = :householdId
              and e.usedAt >= :from
              and e.usedAt < :to
            group by e.itemKey, e.unit
            order by count(e) desc, max(e.usedAt) desc
            """)
    List<ItemTotal> totals(@Param("householdId") UUID householdId,
                           @Param("from") Instant from,
                           @Param("to") Instant to);
}
