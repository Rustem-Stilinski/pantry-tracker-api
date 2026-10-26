package com.usedlog.household;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A group of people sharing one log (a home, an office, a team). */
@Entity
@Table(name = "household")
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "invite_code", nullable = false, length = 16)
    private String inviteCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Household() {
    }

    public Household(String name, String inviteCode, Instant createdAt) {
        this.name = name;
        this.inviteCode = inviteCode;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
