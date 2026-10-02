package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.SourceType;
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
}
