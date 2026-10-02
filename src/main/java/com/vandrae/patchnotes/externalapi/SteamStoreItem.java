package com.vandrae.patchnotes.externalapi;

import java.util.List;

/**
 * What Steam's store knows about one game, trimmed to what we show, rank and filter by.
 *
 * @param shortDescription the store page's one-paragraph summary (may contain markup; clean before display)
 * @param imagePath        the small cover image, RELATIVE to Steam's asset CDN (e.g. {@code steam/apps/1/abc/capsule_231x87.jpg?t=1});
 *                         null when the store has no image. Relative so the CDN host can change without touching stored data.
 * @param iconPath         the small SQUARE icon, relative to Steam's community-icon CDN ({@code {appid}/{hash}.jpg}); null if none
 * @param reviewCount      number of user reviews: the main popularity signal
 * @param reviewScore      Steam's review level, 0 (no reviews) to 9 (Overwhelmingly Positive)
 * @param percentPositive  share of positive reviews (0-100), or null if Steam gave none
 * @param tagIds           the game's top user-assigned store tags, most relevant first; genres are among them
 */
public record SteamStoreItem(long appId, String shortDescription, String imagePath, String iconPath, int reviewCount,
                             int reviewScore, Integer percentPositive, List<Long> tagIds) {
}
