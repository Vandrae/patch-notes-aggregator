/**
 * Clients for third-party APIs. They return the provider's own DTOs untouched; turning those into our
 * model is the fetch module's job (normalization), so a provider change never leaks past this module.
 */
@ApplicationModule(displayName = "External APIs", allowedDependencies = {})
package com.vandrae.patchnotes.externalapi;

import org.springframework.modulith.ApplicationModule;
