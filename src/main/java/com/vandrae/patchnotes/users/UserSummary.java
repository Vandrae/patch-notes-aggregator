package com.vandrae.patchnotes.users;

/**
 * @param steamId the 64-bit SteamID as a string: it exceeds JavaScript's safe integer range (2^53),
 *                so a JSON number would be silently corrupted in the browser
 */
public record UserSummary(long id, String steamId, String personaName, String avatarUrl) {
}
