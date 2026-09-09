package com.Gonzxxr.spotify_recommender.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spotify")
public record SpotifyProperties(String clientId, String clientSecret, String redirectUri) {
}
