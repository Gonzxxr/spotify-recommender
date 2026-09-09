package com.Gonzxxr.spotify_recommender.lastfm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LastFmSimilarTracksResponse(@JsonProperty("similartracks") SimilarTracks similarTracks) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimilarTracks(List<Track> track) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(String name, Artist artist, String match) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(String name) {
    }
}
