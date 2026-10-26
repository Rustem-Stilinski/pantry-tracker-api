package com.usedlog.price;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A store a household shops at ("Giant", "Trader Joe's"), unique per household by lower-cased name. */
@Entity
@Table(name = "store")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "name_key", nullable = false, length = 80)
    private String nameKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Store() {
    }

    public Store(UUID householdId, String name, String nameKey, Instant createdAt) {
        this.householdId = householdId;
        this.name = name;
        this.nameKey = nameKey;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }
}
