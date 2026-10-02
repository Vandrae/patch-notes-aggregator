package com.vandrae.patchnotes.users.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, Long> {

    boolean existsByUserIdAndGameId(Long userId, Long gameId);

    List<WatchlistEntry> findByUserIdOrderByAddedAtDesc(Long userId);

    long deleteByUserIdAndGameId(Long userId, Long gameId);

    long deleteByUserId(Long userId);

    @Query("select w.gameId from WatchlistEntry w where w.userId = :userId")
    List<Long> findGameIdsByUserId(Long userId);

    /** Drives the poller: only games somebody actually follows get fetched (design note #1). */
    @Query("select distinct w.gameId from WatchlistEntry w")
    List<Long> findDistinctGameIds();
}
