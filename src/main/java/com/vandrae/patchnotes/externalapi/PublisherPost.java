package com.vandrae.patchnotes.externalapi;

import java.time.Instant;

/**
 * One post from a publisher's own feed (an RSS item, an Atom entry or a help-centre article), reduced to the fields every
 * such feed has. The body is the publisher's HTML, untouched: turning it into a short plain-text summary is the fetch
 * module's job, as it is for Steam's own news items.
 *
 * @param externalId the feed's own stable id for the post (RSS guid, Atom id, help-centre article id); the dedup key
 * @param url        where the post lives on the publisher's site; the reader is sent here for the full notes
 * @param html       the post's body as the publisher wrote it; may be empty
 */
public record PublisherPost(String externalId, String title, String url, String html, Instant publishedAt) {
}
