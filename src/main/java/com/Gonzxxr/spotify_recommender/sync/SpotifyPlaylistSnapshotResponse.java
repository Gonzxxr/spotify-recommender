package com.Gonzxxr.spotify_recommender.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyPlaylistSnapshotResponse(
        @JsonProperty("snapshot_id") String snapshotId
) {
}
