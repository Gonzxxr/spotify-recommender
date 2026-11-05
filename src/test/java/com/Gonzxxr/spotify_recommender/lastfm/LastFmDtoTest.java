package com.Gonzxxr.spotify_recommender.lastfm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LastFmDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void similarTracksResponseMapsRenamedRootAndIgnoresUnknownFields() {
        String json = """
                {"similartracks":{"track":[{"name":"S","match":"0.5","playcount":"9","artist":{"name":"A","mbid":"x"}}],
                 "@attr":{"artist":"foo"}}}""";

        LastFmSimilarTracksResponse response = mapper.readValue(json, LastFmSimilarTracksResponse.class);

        assertThat(response.similarTracks().track()).hasSize(1);
        assertThat(response.similarTracks().track().get(0).name()).isEqualTo("S");
        assertThat(response.similarTracks().track().get(0).artist().name()).isEqualTo("A");
        assertThat(response.similarTracks().track().get(0).match()).isEqualTo("0.5");
    }

    @Test
    void topTagsResponseMapsRenamedRoot() {
        LastFmTopTagsResponse response = mapper.readValue(
                "{\"toptags\":{\"tag\":[{\"name\":\"jazz\",\"count\":1}],\"@attr\":{}}}", LastFmTopTagsResponse.class);

        assertThat(response.topTags().tag()).extracting(LastFmTopTagsResponse.Tag::name).containsExactly("jazz");
    }
}
