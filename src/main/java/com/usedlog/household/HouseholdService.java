package com.usedlog.household;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.usedlog.common.Text;
import com.usedlog.household.HouseholdDtos.HouseholdResponse;
import com.usedlog.household.HouseholdDtos.HouseholdSummary;
import com.usedlog.household.HouseholdDtos.MemberResponse;
import com.usedlog.household.HouseholdMember.Role;
import com.usedlog.user.AppUser;
import com.usedlog.user.AppUserRepository;

@Service
public class HouseholdService {

    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final AppUserRepository users;
    private final Clock clock;

    public HouseholdService(HouseholdRepository households, HouseholdMemberRepository members,
                            AppUserRepository users, Clock clock) {
        this.households = households;
        this.members = members;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Every household-scoped endpoint calls this first. Non-members get 404 rather than 403,
     * so the API doesn't reveal which household ids exist.
     */
    @Transactional(readOnly = true)
    public void requireMember(UUID householdId, UUID userId) {
        if (!members.existsByHouseholdIdAndUserId(householdId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found.");
        }
    }

    @Transactional(readOnly = true)
    public List<HouseholdSummary> listForUser(UUID userId) {
        List<HouseholdMember> memberships = members.findByUserIdOrderByJoinedAtAsc(userId);
        Map<UUID, Household> byId = households.findAllById(
                        memberships.stream().map(HouseholdMember::getHouseholdId).toList())
                .stream()
                .collect(Collectors.toMap(Household::getId, Function.identity()));
        return memberships.stream()
                .map(m -> byId.get(m.getHouseholdId()))
                .filter(h -> h != null)
                .map(h -> new HouseholdSummary(h.getId(), h.getName()))
                .toList();
    }

    @Transactional
    public HouseholdResponse create(UUID userId, String name) {
        Household household = households.save(new Household(Text.clean(name), uniqueInviteCode(), clock.instant()));
        members.save(new HouseholdMember(household.getId(), userId, Role.OWNER, clock.instant()));
        return toResponse(household);
    }

    @Transactional
    public HouseholdResponse join(UUID userId, String inviteCode) {
        Household household = households.findByInviteCode(InviteCodes.normalize(inviteCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No household uses that invite code. Check the code and try again."));
        if (!members.existsByHouseholdIdAndUserId(household.getId(), userId)) {
            members.save(new HouseholdMember(household.getId(), userId, Role.MEMBER, clock.instant()));
        }
        return toResponse(household);
    }

    @Transactional(readOnly = true)
    public HouseholdResponse get(UUID householdId, UUID userId) {
        requireMember(householdId, userId);
        Household household = households.findById(householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found."));
        return toResponse(household);
    }

    private HouseholdResponse toResponse(Household household) {
        List<HouseholdMember> list = members.findByHouseholdIdOrderByJoinedAtAsc(household.getId());
        Map<UUID, AppUser> people = users.findAllById(list.stream().map(HouseholdMember::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<MemberResponse> memberResponses = list.stream()
                .map(m -> new MemberResponse(
                        m.getUserId(),
                        people.containsKey(m.getUserId()) ? people.get(m.getUserId()).getDisplayName() : "",
                        m.getRole().name()))
                .toList();
        return new HouseholdResponse(household.getId(), household.getName(), household.getInviteCode(), memberResponses);
    }

    private String uniqueInviteCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = InviteCodes.generate();
            if (!households.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite code");
    }
}
