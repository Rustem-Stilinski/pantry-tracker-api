package com.usedlog.household;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.usedlog.household.HouseholdDtos.CreateHouseholdRequest;
import com.usedlog.household.HouseholdDtos.HouseholdResponse;
import com.usedlog.household.HouseholdDtos.HouseholdSummary;
import com.usedlog.household.HouseholdDtos.JoinHouseholdRequest;
import com.usedlog.security.AppUserPrincipal;

@RestController
@RequestMapping("/api/households")
public class HouseholdController {

    private final HouseholdService service;

    public HouseholdController(HouseholdService service) {
        this.service = service;
    }

    /** Households the signed-in person belongs to. */
    @GetMapping
    public List<HouseholdSummary> mine(@AuthenticationPrincipal AppUserPrincipal me) {
        return service.listForUser(me.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HouseholdResponse create(@AuthenticationPrincipal AppUserPrincipal me,
                                    @Valid @RequestBody CreateHouseholdRequest body) {
        return service.create(me.getId(), body.name());
    }

    @PostMapping("/join")
    public HouseholdResponse join(@AuthenticationPrincipal AppUserPrincipal me,
                                  @Valid @RequestBody JoinHouseholdRequest body) {
        return service.join(me.getId(), body.inviteCode());
    }

    @GetMapping("/{householdId}")
    public HouseholdResponse get(@AuthenticationPrincipal AppUserPrincipal me,
                                 @PathVariable UUID householdId) {
        return service.get(householdId, me.getId());
    }
}
