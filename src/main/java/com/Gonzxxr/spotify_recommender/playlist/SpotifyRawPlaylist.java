package com.Gonzxxr.spotify_recommender.playlist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyRawPlaylist(String id, String name, List<SpotifyImageRef> images, TrackCount items, Owner owner) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrackCount(int total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String id) {
    }
}
