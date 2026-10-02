package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.IncomingArticle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The titles below are real Dragonwilds news items (Steam app 1374490), including the awkward ones: patches
 * that never say "patch", and non-patches that say "update" or contain a version number.
 */
class SteamNewsNormalizerTest {

    private final SteamNewsNormalizer normalizer = new SteamNewsNormalizer();

    private static SteamNewsItem item(String title, int feedType, List<String> tags, String contents) {
        return new SteamNewsItem("1844751498233777", title, "https://example.test/news/1", true, "Mod Doom",
                contents, "Community Announcements", 1790676675L, "steam_community_announcements", feedType, 1374490L, tags);
    }

    @ParameterizedTest(name = "first-party \"{0}\" -> patch notes = {1}")
    @CsvSource(delimiterString = "=>", textBlock = """
            1.0.0.5 Patch Notes - Releasing Friday                        => true
            Eye on Ashenfall | 1.0.0.4 Patch                              => true
            0.12.0.4 is live!                                             => true
            0.12.1 is now live!                                           => true
            0.12.0.2 & Investigating Issues                               => true
            Eye on Ashenfall | 0.12.0                                     => true
            Hotfix 1.2 is live                                            => true
            VALORANT 9.10 Patch Notes                                     => true
            Update 12 is here                                             => true
            Our 1.0 Check-In Survey is now Live!                          => false
            Our 0.12.1 Update Survey is now live!                         => false
            Combat Rework: Stamina, Parrying, Dodging | 0.12.1 Preview    => false
            Kuldra's Saga - An Update From Mod Dutch                      => false
            The Wrath of the Dragon Event is live TOMORROW!               => false
            Dragonwilds Is Leaving Early Access. Our Price Isn't.         => false
            0.12 - Known Issues and Vital Information                     => false
            """)
    void classifiesFirstPartyPostsByTitle(String title, boolean expected) {
        assertThat(normalizer.isPatchNotes(item(title.strip(), 1, null, "body"))).isEqualTo(expected);
    }

    @Test
    void trustsTheDeveloperPatchnotesTagEvenWhenTheTitleSaysNothing() {
        assertThat(normalizer.isPatchNotes(item("Something completely unrelated", 1, List.of("patchnotes"), "x"))).isTrue();
    }

    @Test
    void ignoresModerationTagsWhenClassifying() {
        var noise = List.of("mod_reviewed", "ModAct_1894963720_1790172935_0", "mod_require_rereview");
        assertThat(normalizer.isPatchNotes(item("Our 1.0 Check-In Survey is now Live!", 1, noise, "x"))).isFalse();
        assertThat(normalizer.isPatchNotes(item("1.0.0.5 Patch Notes - Releasing Friday", 1, noise, "x"))).isTrue();
    }

    @Test
    void neverTreatsThirdPartyFeedsAsPatchNotes() {
        // press articles and SteamDB posts share the feed; even a "patch notes" headline from them is not ours
        assertThat(normalizer.isPatchNotes(item("Dragonwilds 1.0.0.4 patch notes: everything that changed", 0, null, "x"))).isFalse();
        assertThat(normalizer.isPatchNotes(item("Steam Global Top Sellers for week of 15 Sep", 0, null, "x"))).isFalse();
    }

    @Test
    void mapsFieldsAndConvertsUnixSecondsToUtcInstant() {
        IncomingArticle article = normalizer.normalize(
                item("  0.12.0.4 is live!  ", 1, List.of("patchnotes"), "[p]Fixed fishing.[/p]")).orElseThrow();

        assertThat(article.externalId()).isEqualTo("1844751498233777");
        assertThat(article.title()).isEqualTo("0.12.0.4 is live!");
        assertThat(article.url()).isEqualTo("https://example.test/news/1");
        assertThat(article.type()).isEqualTo(ArticleType.PATCH_NOTES);
        assertThat(article.publishedAt()).isEqualTo(Instant.ofEpochSecond(1790676675L));
        assertThat(article.summary()).isEqualTo("Fixed fishing.");
    }

    @Test
    void dropsItemsThatAreNotPatchNotes() {
        assertThat(normalizer.normalize(item("Our 1.0 Check-In Survey is now Live!", 1, null, "x"))).isEmpty();
    }

    @Test
    void flattensBbcodeListsWithoutGluingItemsTogether() {
        String body = "[p]Hello adventurers![/p][list][*][p]Resolved an issue where fishing locations were unusable.[/p][/*]"
                + "[*][p]Tok-Xil enemies now drop items.[/p][/*][/list]";

        assertThat(SteamNewsNormalizer.summarize(body))
                .isEqualTo("Hello adventurers! Resolved an issue where fishing locations were unusable. Tok-Xil enemies now drop items.");
    }

    @Test
    void stripsImagesHtmlAndDecodesEntities() {
        String body = "[img]{STEAM_CLAN_IMAGE}/45094850/banner.png[/img][p]Fish &amp; chips <b>fixed</b>[/p]"
                + "{STEAM_CLAN_IMAGE}/leftover.png [url=https://example.test]see more[/url]";

        assertThat(SteamNewsNormalizer.summarize(body)).isEqualTo("Fish & chips fixed see more");
    }

    // Steam writes a literal "[" as "\[" so it can't be mistaken for a BBCode tag. These are real snippets from
    // Counter-Strike 2, Rust, Deadlock and Apex Legends patch notes.

    @Test
    void unescapesBracketsSteamEscapedWithABackslash_counterStrikeSectionHeadings() {
        String body = "[p]\\[ RUSH ][/p][list][*][p]Various fixes[/p][/*][*][p]Added a new spawn[/p][/*][/list]";

        assertThat(SteamNewsNormalizer.summarize(body)).isEqualTo("[ RUSH ] Various fixes Added a new spawn");
    }

    @Test
    void unescapesBracketsInsideFormattingTags() {
        assertThat(SteamNewsNormalizer.summarize("[p][b]\\[ General ][/b][/p][p]- Fixed a crash[/p]"))
                .isEqualTo("[ General ] - Fixed a crash");                      // Deadlock
        assertThat(SteamNewsNormalizer.summarize("[p]Standard Stock \\[Corrupted][/b][/p]"))
                .isEqualTo("Standard Stock [Corrupted]");                       // Apex: the escaped "[" and the real "[/b]" side by side
    }

    @Test
    void unescapesBracketsInTheMiddleOfAnySentence() {
        assertThat(SteamNewsNormalizer.summarize("[p]\\[US East] Facepunch 1 is back.[/p]")).isEqualTo("[US East] Facepunch 1 is back.");
        assertThat(SteamNewsNormalizer.summarize("[p]except for \\[Assault] mode.[/p]")).isEqualTo("except for [Assault] mode.");
    }

    @Test
    void anEscapedBracketIsLiteralTextEvenWhenItLooksLikeATag() {
        // "\[b]" means the characters [b], not the start of bold. The real tag that follows is still removed.
        assertThat(SteamNewsNormalizer.summarize("[p]Use \\[b] for bold[/p]")).isEqualTo("Use [b] for bold");
        assertThat(SteamNewsNormalizer.summarize("[p]Press \\[Esc\\] to close[/p]")).isEqualTo("Press [Esc] to close");
    }

    // War Thunder's notes use tags with attributes ([p align="justify"]), not just the [url=...] form

    @Test
    void stripsTagsThatCarryAttributes_warThunder() {
        assertThat(SteamNewsNormalizer.summarize("[p align=\"justify\"]A bug that caused a decrease was fixed.[/p]"))
                .isEqualTo("A bug that caused a decrease was fixed.");
        assertThat(SteamNewsNormalizer.summarize("Aircraft [p align=\"justify\"]F-102A (all variants)[/p][p align=\"justify\"]Next change[/p]"))
                .isEqualTo("Aircraft F-102A (all variants) Next change");
        assertThat(SteamNewsNormalizer.summarize("[h2 align='center' class=big]Title[/h2][img src=\"x.png\" width=10][/img]Text"))
                .isEqualTo("Title Text");
        assertThat(SteamNewsNormalizer.summarize("[url=https://example.test/x]link text[/url] and [list=1][*]one[/*][/list]"))
                .isEqualTo("link text and one");
    }

    @Test
    void doesNotMistakePlainBracketedTextForATag() {
        assertThat(SteamNewsNormalizer.summarize("[p]\\[ RUSH ] fixes[/p]")).isEqualTo("[ RUSH ] fixes");
        assertThat(SteamNewsNormalizer.summarize("[p]Use [p and q] as keys, see [b is bold][/p]")).isEqualTo("Use [p and q] as keys, see [b is bold]");
    }

    @Test
    void leavesOtherBackslashesAlone() {
        assertThat(SteamNewsNormalizer.summarize("[p]C:\\Games\\Steam and 5\\2[/p]")).isEqualTo("C:\\Games\\Steam and 5\\2");
    }

    @Test
    void theContentHashChangesWithTheNormalizerVersionSoStoredSummariesGetRefreshed() {
        // summaries already stored with the "\[" bug must be rewritten even though Steam's text did not change
        var current = normalizer.normalize(item("1.2.3 is live", 1, null, "[p]Same text[/p]")).orElseThrow();

        assertThat(current.contentHash())
                .as("must differ from the plain hash of the text that was used before the version was mixed in")
                .isNotEqualTo(sha256Hex("1.2.3 is live\n[p]Same text[/p]"));
    }

    private static String sha256Hex(String value) throws RuntimeException {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void keepsBracketedTextThatIsNotBbcode() {
        assertThat(SteamNewsNormalizer.summarize("[p][Fixed] crash on login[/p]")).isEqualTo("[Fixed] crash on login");
    }

    @Test
    void truncatesLongBodiesOnAWordBoundary() {
        String body = "[p]" + "lorem ipsum dolor ".repeat(60) + "[/p]";

        String summary = SteamNewsNormalizer.summarize(body);

        assertThat(summary).endsWith("…");
        assertThat(summary.length()).isLessThanOrEqualTo(SteamNewsNormalizer.SUMMARY_MAX_CHARS + 1);
        assertThat(summary).matches("(?s).* (lorem|ipsum|dolor)…"); // ends on a whole word, never mid-word
    }

    @Test
    void contentHashChangesWhenTheBodyIsEditedButNotOtherwise() {
        var original = normalizer.normalize(item("1.2.3 is live", 1, null, "[p]Original[/p]")).orElseThrow();
        var same = normalizer.normalize(item("1.2.3 is live", 1, null, "[p]Original[/p]")).orElseThrow();
        var edited = normalizer.normalize(item("1.2.3 is live", 1, null, "[p]Original[/p][p]Hotfix (Aug 18)[/p]")).orElseThrow();

        assertThat(same.contentHash()).isEqualTo(original.contentHash());
        assertThat(edited.contentHash()).isNotEqualTo(original.contentHash());
    }
}
