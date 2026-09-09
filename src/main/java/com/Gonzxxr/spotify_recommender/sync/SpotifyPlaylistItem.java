package com.Gonzxxr.spotify_recommender.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyPlaylistItem(
        @JsonProperty("added_at") Instant addedAt,
        @JsonProperty("item") SpotifyTrackRef track
) {
}
