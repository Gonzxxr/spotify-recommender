package com.Gonzxxr.spotify_recommender.lastfm;

import com.Gonzxxr.spotify_recommender.support.MockHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LastFmClientTest {

    private MockHttp http;
    private LastFmClient client;

    @BeforeEach
    void setUp() {
        http = MockHttp.create();
        client = new LastFmClient(new LastFmProperties("key123"), http.builder());
    }

    @Test
    void parsesSimilarTracks() {
        http.server().expect(requestTo(containsString("method=track.getsimilar")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"similartracks":{"track":[
                          {"name":"Song A","match":"0.87","artist":{"name":"Artist A"},"url":"ignored"},
                          {"name":"Song B","match":"0.31","artist":{"name":"Artist B"}}
                        ]}}""", MediaType.APPLICATION_JSON));

        List<SimilarTrack> result = client.getSimilarTracks("Radiohead", "Creep", 5);

        assertThat(result).containsExactly(
                new SimilarTrack("Song A", "Artist A", 0.87),
                new SimilarTrack("Song B", "Artist B", 0.31));
        http.server().verify();
    }

    @Test
    void sendsApiKeyLimitAndJsonFormat() {
        http.server().expect(requestTo(containsString("api_key=key123")))
                .andExpect(requestTo(containsString("limit=5")))
                .andExpect(requestTo(containsString("format=json")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getSimilarTracks("a", "b", 5);

        http.server().verify();
    }

    @Test
    void encodesSpecialCharactersInArtistAndTrack() {
        http.server().expect(requestTo(containsString("artist=Guns%20N%27%20Roses")))
                .andExpect(requestTo(containsString("track=Sweet%20Child%20O%E2%80%99%20Mine%20%26%20more")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getSimilarTracks("Guns N' Roses", "Sweet Child O’ Mine & more", 5);

        http.server().verify();
    }

    @Test
    void unparseableMatchBecomesZero() {
        http.server().expect(requestTo(containsString("track.getsimilar")))
                .andRespond(withSuccess("""
                        {"similartracks":{"track":[{"name":"X","match":"abc","artist":{"name":"Y"}}]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getSimilarTracks("a", "b", 5)).containsExactly(new SimilarTrack("X", "Y", 0.0));
    }

    @Test
    void missingMatchAndMissingArtistAreTolerated() {
        http.server().expect(requestTo(containsString("track.getsimilar")))
                .andRespond(withSuccess("""
                        {"similartracks":{"track":[{"name":"X"}]}}""", MediaType.APPLICATION_JSON));

        assertThat(client.getSimilarTracks("a", "b", 5)).containsExactly(new SimilarTrack("X", null, 0.0));
    }

    @Test
    void emptyOrUnexpectedBodiesYieldEmptyList() {
        http.server().expect(requestTo(containsString("track.getsimilar")))
                .andRespond(withSuccess("{\"similartracks\":{}}", MediaType.APPLICATION_JSON));
        assertThat(client.getSimilarTracks("a", "b", 5)).isEmpty();

        http.server().reset();
        http.server().expect(requestTo(containsString("track.getsimilar")))
                .andRespond(withSuccess("{\"error\":6,\"message\":\"Track not found\"}", MediaType.APPLICATION_JSON));
        assertThat(client.getSimilarTracks("a", "b", 5)).isEmpty();
    }

    @Test
    void httpErrorYieldsEmptyListInsteadOfThrowing() {
        http.server().expect(requestTo(containsString("track.getsimilar")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.getSimilarTracks("a", "b", 5)).isEmpty();
    }

    @Test
    void topTagIsTheFirstOne() {
        http.server().expect(requestTo(containsString("method=artist.gettoptags")))
                .andRespond(withSuccess("""
                        {"toptags":{"tag":[{"name":"rock","count":100},{"name":"alternative","count":80}]}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getTopTagForArtist("Radiohead")).contains("rock");
        http.server().verify();
    }

    @Test
    void topTagIsEmptyWhenThereAreNoTags() {
        http.server().expect(requestTo(containsString("artist.gettoptags")))
                .andRespond(withSuccess("{\"toptags\":{\"tag\":[]}}", MediaType.APPLICATION_JSON));
        assertThat(client.getTopTagForArtist("x")).isEqualTo(Optional.empty());

        http.server().reset();
        http.server().expect(requestTo(containsString("artist.gettoptags")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.getTopTagForArtist("x")).isEmpty();
    }

    @Test
    void topTagIsEmptyOnHttpError() {
        http.server().expect(requestTo(containsString("artist.gettoptags")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.getTopTagForArtist("x")).isEmpty();
    }
}
