package com.Gonzxxr.spotify_recommender.playlist;

import com.Gonzxxr.spotify_recommender.support.MockHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PlaylistServiceTest {

    private static final String URL = "https://api.spotify.com/v1/me/playlists?limit=50";

    private MockHttp http;
    private PlaylistService service;

    @BeforeEach
    void setUp() {
        http = MockHttp.create();
        service = new PlaylistService(http.builder());
    }

    @Test
    void returnsOnlyPlaylistsOwnedByTheUserAndMapsFields() {
        http.server().expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"p1","name":"Mine","owner":{"id":"me"},"images":[{"url":"http://img/1"},{"url":"http://img/2"}],"items":{"total":42}},
                          {"id":"p2","name":"Followed","owner":{"id":"someone-else"},"images":[],"items":{"total":3}}
                        ],"next":null}""", MediaType.APPLICATION_JSON));

        List<PlaylistSummary> result = service.fetchUserPlaylists("tok", "me");

        assertThat(result).containsExactly(new PlaylistSummary("p1", "Mine", "http://img/1", 42));
        http.server().verify();
    }

    @Test
    void missingImagesAndTrackCountFallBackToNullAndZero() {
        http.server().expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"a","name":"No images","owner":{"id":"me"},"items":{"total":1}},
                          {"id":"b","name":"Empty images","owner":{"id":"me"},"images":[]},
                          {"id":"c","name":"Null images","owner":{"id":"me"},"images":null}
                        ]}""", MediaType.APPLICATION_JSON));

        assertThat(service.fetchUserPlaylists("tok", "me")).containsExactly(
                new PlaylistSummary("a", "No images", null, 1),
                new PlaylistSummary("b", "Empty images", null, 0),
                new PlaylistSummary("c", "Null images", null, 0));
    }

    @Test
    void playlistWithoutOwnerIsIgnored() {
        http.server().expect(requestTo(URL))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"x\",\"name\":\"Ownerless\"}]}", MediaType.APPLICATION_JSON));

        assertThat(service.fetchUserPlaylists("tok", "me")).isEmpty();
    }

    @Test
    void emptyBodyOrNullItemsGiveEmptyList() {
        http.server().expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(service.fetchUserPlaylists("tok", "me")).isEmpty();
    }

    @Test
    void spotifyErrorsPropagateSoTheControllerCanMapThem() {
        http.server().expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> service.fetchUserPlaylists("tok", "me"))
                .isInstanceOf(HttpClientErrorException.TooManyRequests.class);
    }
}
