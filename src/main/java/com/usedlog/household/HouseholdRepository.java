package com.usedlog.household;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdRepository extends JpaRepository<Household, UUID> {

    Optional<Household> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
