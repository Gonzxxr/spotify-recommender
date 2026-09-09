package com.Gonzxxr.spotify_recommender.playlist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyOwnedPlaylistsResponse(List<SpotifyRawPlaylist> items, String next) {
}
