package com.Gonzxxr.spotify_recommender.sync;

import com.Gonzxxr.spotify_recommender.support.MockHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpotifySearchServiceTest {

    private static final String HIT = """
            {"tracks":{"items":[{"id":"trk-1","name":"Song","album":{"images":[{"url":"http://img/cover"}]}}]}}""";
    private static final String MISS = "{\"tracks\":{\"items\":[]}}";

    private MockHttp http;
    private SpotifySearchService service;

    @BeforeEach
    void setUp() {
        http = MockHttp.create();
        service = new SpotifySearchService(http.builder());
    }

    @Test
    void returnsTheFirstHitWithItsCoverArt() {
        http.server().expect(requestTo(containsString("https://api.spotify.com/v1/search")))
                .andExpect(requestTo(containsString("type=track")))
                .andExpect(requestTo(containsString("limit=1")))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        Optional<TrackMatch> match = service.findTrack("tok", "Song", "Artist");

        assertThat(match).contains(new TrackMatch("trk-1", "http://img/cover"));
        http.server().verify();
    }

    @Test
    void queryUsesTrackAndArtistFilters() {
        http.server().expect(requestTo(containsString("q=track:Hey%20Jude%20artist:The%20Beatles")))
                .andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        service.findTrack("tok", "Hey Jude", "The Beatles");

        http.server().verify();
    }

    @Test
    void noResultsGivesEmpty() {
        http.server().expect(requestTo(containsString("/v1/search"))).andRespond(withSuccess(MISS, MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "Nope", "Nobody")).isEmpty();
    }

    @Test
    void hitWithoutAnIdIsTreatedAsNoMatch() {
        http.server().expect(requestTo(containsString("/v1/search")))
                .andRespond(withSuccess("{\"tracks\":{\"items\":[{\"name\":\"no id\"}]}}", MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "a", "b")).isEmpty();
    }

    @Test
    void emptyBodyGivesEmpty() {
        http.server().expect(requestTo(containsString("/v1/search"))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "a", "b")).isEmpty();
    }

    @Test
    void repeatedLookupIsServedFromCacheWithoutAnotherHttpCall() {
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        Optional<TrackMatch> first = service.findTrack("tok", "Song", "Artist");
        Optional<TrackMatch> second = service.findTrack("tok", "Song", "Artist");

        assertThat(second).isEqualTo(first).isPresent();
        http.server().verify();
    }

    @Test
    void cacheKeyIgnoresCaseAndSurroundingWhitespace() {
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        service.findTrack("tok", "Song", "Artist");
        Optional<TrackMatch> again = service.findTrack("tok", "  SONG ", "artist  ");

        assertThat(again).isPresent();
        http.server().verify();
    }

    @Test
    void confirmedMissesAreCachedToo() {
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withSuccess(MISS, MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "x", "y")).isEmpty();
        assertThat(service.findTrack("tok", "x", "y")).isEmpty();
        http.server().verify();
    }

    @Test
    void cacheDistinguishesDifferentTracks() {
        http.server().expect(once(), requestTo(containsString("Song%20A"))).andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));
        http.server().expect(once(), requestTo(containsString("Song%20B"))).andRespond(withSuccess(MISS, MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "Song A", "X")).isPresent();
        assertThat(service.findTrack("tok", "Song B", "X")).isEmpty();
        http.server().verify();
    }

    @Test
    void serverErrorIsNotCachedSoALaterCallRetries() {
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "Song", "Artist")).isEmpty();
        assertThat(service.findTrack("tok", "Song", "Artist")).isPresent();
        http.server().verify();
    }

    @Test
    void rateLimitBlocksEverySubsequentSearchWithoutHittingSpotify() {
        http.server().expect(once(), requestTo(containsString("/v1/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"));

        assertThat(service.findTrack("tok", "First", "A")).isEmpty();
        // different track, so the cache cannot be what answers: it is the circuit breaker
        assertThat(service.findTrack("tok", "Second", "B")).isEmpty();
        http.server().verify();
    }

    @Test
    void rateLimitedResultIsNotCached() {
        http.server().expect(once(), requestTo(containsString("/v1/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        http.server().expect(once(), requestTo(containsString("/v1/search"))).andRespond(withSuccess(HIT, MediaType.APPLICATION_JSON));

        assertThat(service.findTrack("tok", "Song", "Artist")).isEmpty();
        assertThat(service.findTrack("tok", "Song", "Artist")).isPresent();
        http.server().verify();
    }

    @Test
    void rateLimitWithoutAValidRetryAfterStillBlocks() {
        http.server().expect(once(), requestTo(containsString("/v1/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "soon"));

        assertThat(service.findTrack("tok", "First", "A")).isEmpty();
        assertThat(service.findTrack("tok", "Second", "B")).isEmpty();
        http.server().verify();
    }

    @Test
    void uncachedCallsAreSpacedAtLeastAboutFourHundredMilliseconds() {
        http.server().expect(requestTo(containsString("/v1/search"))).andRespond(withSuccess(MISS, MediaType.APPLICATION_JSON));
        http.server().expect(requestTo(containsString("/v1/search"))).andRespond(withSuccess(MISS, MediaType.APPLICATION_JSON));

        service.findTrack("tok", "one", "a");
        long start = System.nanoTime();
        service.findTrack("tok", "two", "b");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(350);
    }
}
