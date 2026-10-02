package com.vandrae.patchnotes.feed.internal;

import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.IncomingArticle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "article")
public class Article {

    private static final int TITLE_MAX = 500;
    private static final int URL_MAX = 1000;
    private static final int SUMMARY_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    @Column(nullable = false, length = URL_MAX)
    private String url;

    @Column(nullable = false, length = SUMMARY_MAX)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "article_type", nullable = false)
    private ArticleType type;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    protected Article() {
    }

    public Article(long gameId, IncomingArticle in, Instant fetchedAt) {
        this.gameId = gameId;
        this.externalId = in.externalId();
        updateFrom(in, fetchedAt);
    }

    public void updateFrom(IncomingArticle in, Instant fetchedAt) {
        this.title = abbreviate(in.title(), TITLE_MAX);
        this.url = abbreviate(in.url(), URL_MAX);
        this.summary = abbreviate(in.summary(), SUMMARY_MAX);
        this.type = in.type();
        this.publishedAt = in.publishedAt();
        this.contentHash = in.contentHash();
        this.fetchedAt = fetchedAt;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getGameId() {
        return gameId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getSummary() {
        return summary;
    }

    public ArticleType getType() {
        return type;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getContentHash() {
        return contentHash;
    }
}
