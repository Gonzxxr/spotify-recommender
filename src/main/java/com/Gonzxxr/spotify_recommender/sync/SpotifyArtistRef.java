package com.Gonzxxr.spotify_recommender.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyArtistRef(String name) {
}
