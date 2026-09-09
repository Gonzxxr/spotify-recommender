package com.Gonzxxr.spotify_recommender.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifySearchResponse(SpotifyTracksPage tracks) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpotifyTracksPage(List<SpotifyTrackRef> items) {
    }
}
