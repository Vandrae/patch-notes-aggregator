package com.vandrae.patchnotes.users.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_user") // "user" is reserved in most SQL dialects
public class User {

    private static final int PERSONA_MAX = 100;
    private static final int AVATAR_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "steam_id", nullable = false, unique = true)
    private Long steamId;

    @Column(name = "persona_name", nullable = false, length = PERSONA_MAX)
    private String personaName;

    @Column(name = "avatar_url", length = AVATAR_MAX)
    private String avatarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_login_at", nullable = false)
    private Instant lastLoginAt;

    protected User() {
    }

    public User(long steamId, String personaName, String avatarUrl, Instant now) {
        this.steamId = steamId;
        this.lastLoginAt = now;
        applyProfile(personaName, avatarUrl);
    }

    /** Called on every login so the name and avatar track what the user has on Steam. */
    public void recordLogin(String personaName, String avatarUrl, Instant now) {
        this.lastLoginAt = now;
        applyProfile(personaName, avatarUrl);
    }

    private void applyProfile(String personaName, String avatarUrl) {
        this.personaName = truncate(personaName, PERSONA_MAX);
        this.avatarUrl = avatarUrl == null ? null : truncate(avatarUrl, AVATAR_MAX);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getSteamId() {
        return steamId;
    }

    public String getPersonaName() {
        return personaName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }
}
