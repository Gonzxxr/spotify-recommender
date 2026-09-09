package com.Gonzxxr.spotify_recommender.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStore {
    public static final String COOKIE_NAME = "SESSION_ID";
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private record Session(String spotifyUserId, Instant expiresAt) {
    }

    private final Map<String, Session> store = new ConcurrentHashMap<>();

    public String create(String spotifyUserId) {
        String sessionId = UUID.randomUUID().toString();
        store.put(sessionId, new Session(spotifyUserId, Instant.now().plus(SESSION_TTL)));
        return sessionId;
    }

    public Optional<String> resolve(String sessionId) {
        if (sessionId == null) return Optional.empty();
        Session session = store.get(sessionId);
        if (session == null) return Optional.empty();
        if (Instant.now().isAfter(session.expiresAt())) {
            store.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(session.spotifyUserId());
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) store.remove(sessionId);
    }
}
