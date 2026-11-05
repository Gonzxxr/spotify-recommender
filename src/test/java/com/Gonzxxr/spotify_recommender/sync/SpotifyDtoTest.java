package com.Gonzxxr.spotify_recommender.sync;

import com.Gonzxxr.spotify_recommender.auth.SpotifyProfileResponse;
import com.Gonzxxr.spotify_recommender.auth.SpotifyTokenResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyDtoTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void playlistItemMapsSnakeCaseAddedAtAndItemField() {
        String json = """
                {"added_at":"2024-05-01T10:15:30Z","added_by":{"id":"x"},
                 "item":{"id":"t1","name":"Song","artists":[{"name":"A1"},{"name":"A2"}],
                         "album":{"images":[{"url":"http://img/big"},{"url":"http://img/small"}]}}}""";

        SpotifyPlaylistItem item = mapper.readValue(json, SpotifyPlaylistItem.class);

        assertThat(item.addedAt()).isEqualTo(Instant.parse("2024-05-01T10:15:30Z"));
        assertThat(item.track().id()).isEqualTo("t1");
        assertThat(item.track().artists()).extracting(SpotifyArtistRef::name).containsExactly("A1", "A2");
        assertThat(item.track().albumImageUrl()).isEqualTo("http://img/big");
    }

    @Test
    void tracksPageMapsNextLink() {
        SpotifyPlaylistTracksResponse response = mapper.readValue(
                "{\"items\":[],\"next\":\"https://api.spotify.com/next\",\"total\":0}", SpotifyPlaylistTracksResponse.class);

        assertThat(response.items()).isEmpty();
        assertThat(response.next()).isEqualTo("https://api.spotify.com/next");
    }

    @Test
    void snapshotMapsSnakeCase() {
        assertThat(mapper.readValue("{\"snapshot_id\":\"abc\"}", SpotifyPlaylistSnapshotResponse.class).snapshotId())
                .isEqualTo("abc");
    }

    @Test
    void searchResponseMapsFirstTrack() {
        SpotifySearchResponse response = mapper.readValue(
                "{\"tracks\":{\"items\":[{\"id\":\"t\",\"album\":{\"images\":[{\"url\":\"u\"}]}}],\"total\":1}}",
                SpotifySearchResponse.class);

        assertThat(response.tracks().items().get(0).id()).isEqualTo("t");
        assertThat(response.tracks().items().get(0).albumImageUrl()).isEqualTo("u");
    }

    @Test
    void tokenResponseMapsSnakeCase() {
        SpotifyTokenResponse token = mapper.readValue("""
                {"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"Bearer","scope":"x"}""",
                SpotifyTokenResponse.class);

        assertThat(token.accessToken()).isEqualTo("a");
        assertThat(token.refreshToken()).isEqualTo("r");
        assertThat(token.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void tokenResponseWithoutRefreshTokenKeepsItNull() {
        assertThat(mapper.readValue("{\"access_token\":\"a\",\"expires_in\":10}", SpotifyTokenResponse.class)
                .refreshToken()).isNull();
    }

    @Test
    void profileMapsIdAndDisplayName() {
        SpotifyProfileResponse profile = mapper.readValue(
                "{\"id\":\"user1\",\"display_name\":\"Gonza\",\"email\":\"x\"}", SpotifyProfileResponse.class);

        assertThat(profile.spotifyUserId()).isEqualTo("user1");
        assertThat(profile.displayName()).isEqualTo("Gonza");
    }

    @Test
    void albumImageUrlIsNullWhenThereIsNoAlbumOrNoImages() {
        assertThat(new SpotifyTrackRef("t", "n", null, null).albumImageUrl()).isNull();
        assertThat(new SpotifyTrackRef("t", "n", null, new SpotifyTrackRef.SpotifyAlbumRef(null)).albumImageUrl()).isNull();
        assertThat(new SpotifyTrackRef("t", "n", null, new SpotifyTrackRef.SpotifyAlbumRef(java.util.List.of())).albumImageUrl()).isNull();
    }
}
