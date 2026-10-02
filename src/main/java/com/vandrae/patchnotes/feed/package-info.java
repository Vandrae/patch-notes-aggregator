/**
 * Stores normalized articles and serves each user's feed. Every source, whatever it is, hands this module
 * the same {@link com.vandrae.patchnotes.feed.IncomingArticle} shape; nothing here knows where it came from.
 */
@ApplicationModule(displayName = "Feed", allowedDependencies = {"users", "catalog", "events"})
package com.vandrae.patchnotes.feed;

import org.springframework.modulith.ApplicationModule;
