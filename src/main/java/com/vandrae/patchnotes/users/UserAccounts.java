package com.vandrae.patchnotes.users;

import com.vandrae.patchnotes.users.internal.User;
import com.vandrae.patchnotes.users.internal.UserRepository;
import com.vandrae.patchnotes.users.internal.WatchlistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Accounts are Steam identities: there is no password, email or registration step. */
@Service
public class UserAccounts {

    static final String FALLBACK_PERSONA = "Steam user";

    private final UserRepository users;
    private final WatchlistRepository watchlist;

    UserAccounts(UserRepository users, WatchlistRepository watchlist) {
        this.users = users;
        this.watchlist = watchlist;
    }

    /**
     * Signing in with Steam both logs in and signs up: the first time a SteamID is seen it gets an account.
     * The name/avatar are refreshed on every login. A missing persona (Steam profile lookup failed or no API key)
     * falls back to a generic name rather than blocking the login.
     */
    @Transactional
    public UserSummary findOrCreateBySteamId(long steamId, String personaName, String avatarUrl) {
        String persona = personaName == null || personaName.isBlank() ? FALLBACK_PERSONA : personaName.strip();
        Instant now = Instant.now();
        User user = users.findBySteamId(steamId).orElse(null);
        if (user == null) {
            user = users.save(new User(steamId, persona, avatarUrl, now));
        } else {
            // a failed lookup must not overwrite a good name we already have with the fallback
            boolean lookupFailed = FALLBACK_PERSONA.equals(persona) && avatarUrl == null;
            user.recordLogin(lookupFailed ? user.getPersonaName() : persona,
                    lookupFailed ? user.getAvatarUrl() : avatarUrl, now);
        }
        return toSummary(user);
    }

    @Transactional(readOnly = true)
    public Optional<UserSummary> findById(long userId) {
        return users.findById(userId).map(UserAccounts::toSummary);
    }

    /** Deletes the account and everything that hangs off it (the user's right to leave). */
    @Transactional
    public void delete(long userId) {
        watchlist.deleteByUserId(userId);
        users.deleteById(userId);
    }

    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), Long.toString(user.getSteamId()), user.getPersonaName(), user.getAvatarUrl());
    }
}
