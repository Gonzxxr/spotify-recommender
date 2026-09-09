package com.Gonzxxr.spotify_recommender.lastfm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LastFmTopTagsResponse(@JsonProperty("toptags") TopTags topTags) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopTags(List<Tag> tag) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tag(String name) {
    }
}
