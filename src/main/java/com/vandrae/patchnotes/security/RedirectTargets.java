package com.vandrae.patchnotes.security;

import java.net.URI;
import java.net.URISyntaxException;

/** Guards the post-login redirect so the login endpoint can't be used as an open redirect. */
final class RedirectTargets {

    static final String DEFAULT = "/";

    private RedirectTargets() {
    }

    /**
     * Returns {@code next} only if it is a plain same-origin path ("/feed", "/games/7?tab=notes"); anything else
     * (absolute URLs, protocol-relative "//host", backslash tricks, control characters) becomes "/".
     */
    static String sanitize(String next) {
        if (next == null || next.isEmpty() || next.length() > 512) {
            return DEFAULT;
        }
        if (!next.startsWith("/") || next.startsWith("//") || next.contains("\\")) {
            return DEFAULT;
        }
        for (int i = 0; i < next.length(); i++) {
            if (Character.isISOControl(next.charAt(i))) {
                return DEFAULT;
            }
        }
        try {
            URI uri = new URI(next);
            if (uri.getScheme() != null || uri.getHost() != null || uri.getAuthority() != null) {
                return DEFAULT;
            }
        } catch (URISyntaxException e) {
            return DEFAULT;
        }
        return next;
    }
}
