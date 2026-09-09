package com.Gonzxxr.spotify_recommender.playlist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyImageRef(String url) {
}
