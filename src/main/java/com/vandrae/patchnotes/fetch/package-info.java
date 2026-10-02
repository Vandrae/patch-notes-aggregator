/**
 * The ingestion pipeline: poll watched games on a schedule (and on demand when someone starts watching one),
 * pull from the right source, normalize into the common article shape, and hand it to the feed module.
 * Exposes no API of its own; it is driven entirely by the scheduler and by events.
 */
@ApplicationModule(displayName = "Fetch",
        allowedDependencies = {"externalapi", "catalog", "users", "feed", "events"})
package com.vandrae.patchnotes.fetch;

import org.springframework.modulith.ApplicationModule;
