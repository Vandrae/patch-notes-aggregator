package com.vandrae.patchnotes.feed.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    Page<Article> findByGameIdIn(Collection<Long> gameIds, Pageable pageable);

    List<Article> findByGameIdAndExternalIdIn(Long gameId, Collection<String> externalIds);
}
