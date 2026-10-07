package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.IncomingArticle;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps Steam's news items into {@link IncomingArticle}s: decides whether an item is a patch note at all,
 * converts the Unix timestamp to a UTC instant, and boils BBCode bodies down to a plain-text summary.
 *
 * <p>Steam's feed mixes developer posts with press and SteamDB links, and its {@code patchnotes} tag is
 * only set on some posts, so classification goes tag first, then title heuristics (design note #2).
 */
@Component
class SteamNewsNormalizer {

    /** feed_type value Steam uses for posts made by the developer on Steam itself. */
    private static final int FIRST_PARTY_FEED = 1;
    private static final String PATCH_NOTES_TAG = "patchnotes";
    static final int SUMMARY_MAX_CHARS = 280;

    // "0.12.0.4 is live!", "Eye on Ashenfall | 1.0.0.4 Patch": a 3+ part version number
    private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+(?:\\.\\d+)*\\b");
    // patch / patch notes / hotfix / changelog / release notes / "Update 12" -- but NOT a bare "update",
    // which also matches "An Update From Mod Dutch" and "Our 0.12 Update Survey"
    private static final Pattern PATCH_WORDS = Pattern.compile(
            "\\b(?:patch(?:es)?|hotfix(?:es)?|changelog|release\\s+notes|update\\s+v?\\d+)\\b", Pattern.CASE_INSENSITIVE);
    // titles that mention a version but are about something else
    private static final Pattern NOT_A_PATCH = Pattern.compile(
            "\\b(?:survey|preview|roadmap|giveaway)\\b", Pattern.CASE_INSENSITIVE);

    private static final String BBCODE_TAGS =
            "p|h[1-6]|b|i|u|s|strike|list|olist|ul|ol|li|\\*|url|img|quote|code|spoiler|hr|table|tr|td|th|noparse"
                    + "|previewyoutube|youtube|expand|carousel|dynamiclink|center|left|right|sub|sup";
    private static final Pattern BBCODE_IMAGE = Pattern.compile("\\[img[^\\]]*\\].*?\\[/img\\]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // What may follow a tag name: "=value" ([url=...], [list=1]) or attributes ([p align="justify"], used by War Thunder).
    // Anything else, like "[p and q]", is not a tag and stays as text.
    private static final String BBCODE_ARGS =
            "(?:=[^\\]]*|(?:\\s+[a-zA-Z_-]+=(?:\"[^\"]*\"|'[^']*'|[^\\s\\]]*))+)?";
    private static final Pattern BBCODE_BLOCK_BOUNDARY = Pattern.compile(
            "\\[/?(?:p|h[1-6]|list|olist|ul|ol|li|\\*|quote|table|tr|td|th|hr)" + BBCODE_ARGS + "\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern BBCODE_TAG = Pattern.compile(
            "\\[/?(?:" + BBCODE_TAGS + ")" + BBCODE_ARGS + "\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEAM_IMAGE_PLACEHOLDER = Pattern.compile("\\{STEAM_CLAN_IMAGE}\\S*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Private-use characters standing in for Steam's escaped brackets while tags are being stripped. */
    private static final char ESCAPED_OPEN = '';
    private static final char ESCAPED_CLOSE = '';

    /**
     * Mixed into every article's content hash. An article is only rewritten when its hash changes, and the hash is of
     * Steam's text, so improving how summaries are built would never reach articles already stored. Bump this whenever
     * {@link #summarize} changes and every stored summary is rebuilt on the next fetch.
     */
    static final String SUMMARY_VERSION = "summary-v3: unescape \\[ \\], strip tags with attributes";

    /** Empty when the item isn't a patch note and should be dropped. */
    Optional<IncomingArticle> normalize(SteamNewsItem item) {
        if (!isPatchNotes(item)) {
            return Optional.empty();
        }
        String contents = item.contents() == null ? "" : item.contents();
        return Optional.of(new IncomingArticle(
                item.gid(),
                item.title() == null ? "" : item.title().strip(),
                item.url(),
                summarize(contents),
                ArticleType.PATCH_NOTES,
                Instant.ofEpochSecond(item.date()),
                sha256(SUMMARY_VERSION + "\n" + item.title() + "\n" + contents)));
    }

    boolean isPatchNotes(SteamNewsItem item) {
        if (item.tags() != null && item.tags().stream().anyMatch(PATCH_NOTES_TAG::equalsIgnoreCase)) {
            return true; // structured tag from the developer beats any guessing
        }
        if (item.feedType() != FIRST_PARTY_FEED) {
            return false; // press / SteamDB / other third-party feeds are never patch notes
        }
        String title = item.title() == null ? "" : item.title();
        if (NOT_A_PATCH.matcher(title).find()) {
            return false;
        }
        return PATCH_WORDS.matcher(title).find() || VERSION.matcher(title).find();
    }

    /**
     * BBCode/HTML body -> short plain text, cut on a word boundary.
     *
     * <p>Steam escapes a literal bracket with a backslash ({@code \[ RUSH ]}) so that it can't be read as a tag. Those
     * escapes are swapped for stand-in characters first, so the tag-stripping below can't mistake {@code \[b]} for
     * bold, and turned into plain brackets at the end, so a reader sees "[ RUSH ]" and never a stray backslash.
     */
    static String summarize(String contents) {
        String text = contents.replace("\\[", String.valueOf(ESCAPED_OPEN)).replace("\\]", String.valueOf(ESCAPED_CLOSE));
        text = BBCODE_IMAGE.matcher(text).replaceAll(" ");
        text = BBCODE_BLOCK_BOUNDARY.matcher(text).replaceAll(" "); // keep list items from running together
        text = BBCODE_TAG.matcher(text).replaceAll("");
        text = STEAM_IMAGE_PLACEHOLDER.matcher(text).replaceAll(" ");
        text = Jsoup.parse(text).text(); // strips any HTML and decodes entities
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();
        text = text.replace(ESCAPED_OPEN, '[').replace(ESCAPED_CLOSE, ']');

        return PlainText.truncate(text, SUMMARY_MAX_CHARS);
    }

    private static String sha256(String value) {
        return PlainText.sha256(value);
    }
}
