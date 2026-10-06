package com.vandrae.patchnotes.feed.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Page<Article> findByGameIdIn(Collection<Long> gameIds, Pageable pageable);

    Page<Article> findByGameId(Long gameId, Pageable pageable);

    long countByGameId(Long gameId);

    /** When the game's newest stored patch note was published; null when it has none. */
    @Query("select max(a.publishedAt) from Article a where a.gameId = :gameId")
    Instant latestPublishedAt(Long gameId);

    List<Article> findByGameIdAndExternalIdIn(Long gameId, Collection<String> externalIds);
}
