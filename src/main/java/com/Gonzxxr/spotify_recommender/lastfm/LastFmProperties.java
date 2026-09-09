package com.Gonzxxr.spotify_recommender.lastfm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lastfm")
public record LastFmProperties(String apiKey) {
}
