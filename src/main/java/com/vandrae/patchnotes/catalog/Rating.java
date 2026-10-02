package com.vandrae.patchnotes.catalog;

/**
 * A game's user-review rating as Steam shows it ("Very Positive").
 *
 * @param score           Steam's 1-9 level: 9 Overwhelmingly Positive ... 5 Mixed ... 1 Overwhelmingly Negative
 * @param label           the words for that level
 * @param percentPositive share of positive reviews (0-100), or null when unknown
 */
public record Rating(int score, String label, Integer percentPositive) {

    /** Steam's labels by level; index 0 (no reviews) has no rating. */
    private static final String[] LABELS = {
            null, "Overwhelmingly Negative", "Very Negative", "Negative", "Mostly Negative", "Mixed",
            "Mostly Positive", "Positive", "Very Positive", "Overwhelmingly Positive"};

    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 9;

    /** Null when the game has no user reviews (score 0) or the score is outside Steam's scale. */
    public static Rating of(int score, Integer percentPositive) {
        if (score < MIN_SCORE || score > MAX_SCORE) {
            return null;
        }
        return new Rating(score, LABELS[score], percentPositive);
    }

    public static String label(int score) {
        return score < MIN_SCORE || score > MAX_SCORE ? null : LABELS[score];
    }
}
