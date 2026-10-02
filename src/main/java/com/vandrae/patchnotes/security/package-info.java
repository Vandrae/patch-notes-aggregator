/**
 * Authentication: "Sign in through Steam" (OpenID 2.0), the signed session token, and the HTTP security rules
 * for the whole application. There are no passwords: Steam proves who someone is, we issue our own short
 * HS256 JWT, delivered in an HttpOnly cookie so browser JavaScript never sees it.
 */
@ApplicationModule(displayName = "Security", allowedDependencies = {"users", "externalapi"})
package com.vandrae.patchnotes.security;

import org.springframework.modulith.ApplicationModule;
