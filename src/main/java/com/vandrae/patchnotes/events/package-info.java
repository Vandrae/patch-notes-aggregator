/**
 * Shared event contracts. Modules talk to each other through these instead of calling each other directly,
 * which keeps the dependency graph acyclic (e.g. users announces {@link com.vandrae.patchnotes.events.GameWatched}
 * without knowing that fetch reacts to it).
 */
@ApplicationModule(displayName = "Events", allowedDependencies = {})
package com.vandrae.patchnotes.events;

import org.springframework.modulith.ApplicationModule;
