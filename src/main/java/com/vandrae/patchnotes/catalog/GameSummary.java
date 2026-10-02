package com.vandrae.patchnotes.catalog;

/**
 * @param shortDescription the store page's short summary as plain text, or null until details have been fetched
 * @param imageUrl         full https URL of the game's wide cover image on Steam's CDN, or null if there is none
 * @param iconUrl          full https URL of the game's small square icon on Steam's CDN, or null if there is none
 */
public record GameSummary(long id, String name, SourceType sourceType, Long steamAppId,
                          String shortDescription, String imageUrl, String iconUrl) {
}
