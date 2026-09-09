package com.Gonzxxr.spotify_recommender.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OAuthStateStore {
    private final Map<String, Instant> store = new ConcurrentHashMap<>();

    public String create(){
        String state = UUID.randomUUID().toString();
        store.put(state, Instant.now().plus(Duration.ofMinutes(10)));
        return state;
    }

    public boolean consume(String state){
        Instant expiresAt = store.remove(state);
        return expiresAt != null && Instant.now().isBefore(expiresAt);
    }

}
