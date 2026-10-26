package com.usedlog.household;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /api/households. */
public final class HouseholdDtos {

    private HouseholdDtos() {
    }

    public record CreateHouseholdRequest(@NotBlank @Size(max = 80) String name) {
    }

    public record JoinHouseholdRequest(@NotBlank @Size(max = 32) String inviteCode) {
    }

    public record HouseholdSummary(UUID id, String name) {
    }

    public record MemberResponse(UUID userId, String displayName, String role) {
    }

    public record HouseholdResponse(UUID id, String name, String inviteCode, List<MemberResponse> members) {
    }
}
