/**
 * User accounts and each user's watchlist ("User Watch"). Knows nothing about authentication: the security
 * module hashes passwords and issues tokens, this module only stores users and who follows which game.
 */
@ApplicationModule(displayName = "Users", allowedDependencies = {"catalog", "events"})
package com.vandrae.patchnotes.users;

import org.springframework.modulith.ApplicationModule;
