package com.vandrae.patchnotes.users.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Join row User <-> Game. Holds plain ids, not JPA relations, because Game belongs to another module. */
@Entity
@Table(name = "watchlist_entry")
public class WatchlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected WatchlistEntry() {
    }

    public WatchlistEntry(Long userId, Long gameId) {
        this.userId = userId;
        this.gameId = gameId;
    }

    public Long getGameId() {
        return gameId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
