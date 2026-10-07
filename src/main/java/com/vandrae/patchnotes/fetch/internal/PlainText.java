package com.vandrae.patchnotes.fetch.internal;

import org.jsoup.Jsoup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** The pieces every source shares when it turns a publisher's text into a stored summary and a content hash. */
final class PlainText {

    /** How long a stored summary may be: a teaser with a link to the full notes, not a copy of them. */
    static final int SUMMARY_MAX_CHARS = 280;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PlainText() {
    }

    /** HTML body -> short plain text, cut on a word boundary. Tags are dropped and entities decoded; nothing is kept as markup. */
    static String summaryOfHtml(String html) {
        String text = WHITESPACE.matcher(Jsoup.parse(html == null ? "" : html).text()).replaceAll(" ").strip();
        return truncate(text, SUMMARY_MAX_CHARS);
    }

    /** Cuts on a word boundary and adds an ellipsis, unless the text already fits. */
    static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max);
        return text.substring(0, cut > max / 2 ? cut : max).strip() + "…";
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JVM", e);
        }
    }
}
