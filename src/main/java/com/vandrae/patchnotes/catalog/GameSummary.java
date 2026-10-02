package com.vandrae.patchnotes.catalog;

import java.util.List;

/**
 * @param shortDescription the store page's short summary as plain text, or null until details have been fetched
 * @param imageUrl         full https URL of the game's wide cover image on Steam's CDN, or null if there is none
 * @param iconUrl          full https URL of the game's small square icon on Steam's CDN, or null if there is none
 * @param genres           the game's standard Steam genres (empty until details have been fetched)
 * @param rating           the user-review rating, or null for a game without reviews (or before details are fetched)
 * @param ageRating        the ESRB age rating, or null when Steam shows none (many games have none)
 */
public record GameSummary(long id, String name, SourceType sourceType, Long steamAppId,
                          String shortDescription, String imageUrl, String iconUrl,
                          List<Genre> genres, Rating rating, AgeRating ageRating) {
}
