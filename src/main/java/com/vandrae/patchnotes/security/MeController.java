package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** "Who am I", sign out, and delete my account. */
@RestController
@RequestMapping("/api")
class MeController {

    private final UserAccounts users;
    private final SessionCookies cookies;

    MeController(UserAccounts users, SessionCookies cookies) {
        this.users = users;
        this.cookies = cookies;
    }

    /** The app calls this on load to find out whether it is signed in (401 means no). */
    @GetMapping("/me")
    UserSummary me(@AuthenticationPrincipal Jwt jwt) {
        // a still-valid token for an account that was since deleted is not a signed-in user
        return users.findById(Long.parseLong(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    /** Public on purpose: signing out must work even when the session is already gone. Still CSRF-protected. */
    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString()).build();
    }

    @DeleteMapping("/me")
    ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        users.delete(Long.parseLong(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clearSession().toString()).build();
    }
}
