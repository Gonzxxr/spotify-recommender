package com.Gonzxxr.spotify_recommender.sync;

import com.Gonzxxr.spotify_recommender.playlist.SpotifyImageRef;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotifyTrackRef(String id, String name, List<SpotifyArtistRef> artists, SpotifyAlbumRef album) {

    public String albumImageUrl() {
        if (album == null || album.images() == null) return null;
        return album.images().stream().findFirst().map(SpotifyImageRef::url).orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpotifyAlbumRef(List<SpotifyImageRef> images) {
    }
}