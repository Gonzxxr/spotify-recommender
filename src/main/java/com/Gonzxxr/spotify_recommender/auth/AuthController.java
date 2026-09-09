package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/auth/spotify")
public class AuthController {
    private final SpotifyAuthService authService;
    private final OAuthStateStore stateStore;
    private final SessionStore sessionStore;
    private final CurrentUserResolver currentUserResolver;
    private final boolean cookieSecure;

    public AuthController(SpotifyAuthService authService, OAuthStateStore stateStore,
                           SessionStore sessionStore, CurrentUserResolver currentUserResolver,
                           @Value("${app.security.cookie-secure}") boolean cookieSecure) {
        this.authService = authService;
        this.stateStore = stateStore;
        this.sessionStore = sessionStore;
        this.currentUserResolver = currentUserResolver;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/login")
    public ResponseEntity<Void> login() {
        String state = stateStore.create();
        String authorizeUrl = authService.buildAuthorizeUrl(state);
        return ResponseEntity.status(302).location(URI.create(authorizeUrl)).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        if (!stateStore.consume(state)) {
            return ResponseEntity.status(302).location(URI.create("/?error=invalid_state")).build();
        }
        User user = authService.handleCallback(code);
        String sessionId = sessionStore.create(user.getSpotifyUserId());

        String redirectUrl = "/?connected=true&displayName=" + encode(user.getDisplayName());
        return ResponseEntity.status(302)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(sessionId, Duration.ofDays(30)).toString())
                .location(URI.create(redirectUrl))
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@CookieValue(value = SessionStore.COOKIE_NAME, required = false) String sessionId) {
        return currentUserResolver.resolve(sessionId)
                .map(user -> ResponseEntity.ok(Map.of(
                        "spotifyUserId", user.getSpotifyUserId(),
                        "displayName", user.getDisplayName() == null ? "" : user.getDisplayName())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = SessionStore.COOKIE_NAME, required = false) String sessionId) {
        sessionStore.invalidate(sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionStore.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
