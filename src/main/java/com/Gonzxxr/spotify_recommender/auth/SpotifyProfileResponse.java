package com.Gonzxxr.spotify_recommender.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyProfileResponse(
        @JsonProperty("id") String spotifyUserId,
        @JsonProperty("display_name") String displayName
) {
}
