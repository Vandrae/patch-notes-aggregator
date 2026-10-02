package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.catalog.AgeRating;
import com.vandrae.patchnotes.catalog.Genre;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "game")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** {@link NameSearch#normalize} of the name: what searches actually compare against. */
    @Column(name = "name_search", nullable = false)
    private String nameSearch;

    @Column(name = "steam_app_id")
    private Long steamAppId;

    /** Steam's own "last modified" timestamp (epoch seconds), used to spot changes. */
    @Column(name = "steam_last_modified")
    private Long steamLastModified;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    /** The store page's one-paragraph summary; filled in (and cleaned) by the metadata job. */
    @Column(name = "short_description")
    private String shortDescription;

    /** Cover image path relative to Steam's asset CDN; filled in by the metadata job. */
    @Column(name = "image_path")
    private String imagePath;

    /** Small square icon as {appid}/{hash}.jpg, relative to the icon CDN; filled in by the metadata job. */
    @Column(name = "icon_path")
    private String iconPath;

    /** Ranks equally relevant search results. Written by the metadata job, never by JPA. */
    @Column(nullable = false, insertable = false, updatable = false)
    private long popularity;

    /** Steam's 0-9 review level (0 = no reviews). Written by the metadata job, never by JPA. */
    @Column(name = "review_score", nullable = false, insertable = false, updatable = false)
    private int reviewScore;

    @Column(name = "percent_positive", insertable = false, updatable = false)
    private Integer percentPositive;

    /** ESRB age rating, or null when Steam shows none. Written by the metadata job, never by JPA. */
    @Enumerated(EnumType.STRING)
    @Column(name = "age_rating", insertable = false, updatable = false)
    private AgeRating ageRating;

    /** Read-only view: the metadata job writes game_genre with plain JDBC. Batched so a page of results is one query. */
    @ElementCollection
    @CollectionTable(name = "game_genre", joinColumns = @JoinColumn(name = "game_id"))
    @Column(name = "genre")
    @Enumerated(EnumType.STRING)
    @BatchSize(size = 50)
    private Set<Genre> genres = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Game() {
    }

    public Game(String name, Long steamAppId, SourceType sourceType) {
        this.name = name;
        this.nameSearch = NameSearch.normalize(name);
        this.steamAppId = steamAppId;
        this.sourceType = sourceType;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNameSearch() {
        return nameSearch;
    }

    public Long getSteamAppId() {
        return steamAppId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getShortDescription() {
        return shortDescription;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getIconPath() {
        return iconPath;
    }

    public long getPopularity() {
        return popularity;
    }

    public int getReviewScore() {
        return reviewScore;
    }

    public Integer getPercentPositive() {
        return percentPositive;
    }

    public AgeRating getAgeRating() {
        return ageRating;
    }

    public Set<Genre> getGenres() {
        return genres;
    }
}
