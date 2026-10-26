package com.usedlog.household;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, UUID> {

    boolean existsByHouseholdIdAndUserId(UUID householdId, UUID userId);

    List<HouseholdMember> findByUserIdOrderByJoinedAtAsc(UUID userId);

    List<HouseholdMember> findByHouseholdIdOrderByJoinedAtAsc(UUID householdId);
}
