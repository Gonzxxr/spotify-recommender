package com.Gonzxxr.spotify_recommender.sync;

import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.MockHttp;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaylistSyncServiceTest {

    private static final String SNAPSHOT_URL = "https://api.spotify.com/v1/playlists/pl1?fields=snapshot_id";
    private static final String ITEMS_URL = "https://api.spotify.com/v1/playlists/pl1/items";

    @Mock
    private SpotifyAuthService authService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TrackSeenRepository trackSeenRepository;

    private MockHttp http;
    private PlaylistSyncService service;
    private User user;

    @BeforeEach
    void setUp() {
        http = MockHttp.create();
        service = new PlaylistSyncService(authService, userRepository, trackSeenRepository, http.builder());
        user = TestData.user("u1");
        user.setPlaylistId("pl1");
        when(authService.getValidAccessToken(any(User.class))).thenReturn("tok");
    }

    private void expectSnapshot(String snapshotId) {
        http.server().expect(once(), requestTo(SNAPSHOT_URL))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"snapshot_id\":\"%s\"}".formatted(snapshotId), MediaType.APPLICATION_JSON));
    }

    private void expectItems(String url, String json) {
        http.server().expect(once(), requestTo(url)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static String item(String id, String name, String artist, String addedAt) {
        return """
                {"added_at":"%s","item":{"id":%s,"name":"%s","artists":[{"name":"%s"}]}}"""
                .formatted(addedAt, id == null ? "null" : "\"" + id + "\"", name, artist);
    }

    @Test
    void storesEveryNewTrackWithItsMetadata() {
        expectSnapshot("snap-1");
        expectItems(ITEMS_URL, "{\"items\":[%s,%s],\"next\":null}".formatted(
                item("t1", "Song One", "Artist One", "2024-05-01T10:00:00Z"),
                item("t2", "Song Two", "Artist Two", "2024-05-02T10:00:00Z")));

        service.syncUser(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(TrackSeen::getSpotifyTrackId).containsExactly("t1", "t2");
        TrackSeen first = saved.getAllValues().get(0);
        assertThat(first.getUser()).isSameAs(user);
        assertThat(first.getPlaylistId()).isEqualTo("pl1");
        assertThat(first.getTrackName()).isEqualTo("Song One");
        assertThat(first.getArtistName()).isEqualTo("Artist One");
        assertThat(first.getAddedToPlaylistAt()).isEqualTo(Instant.parse("2024-05-01T10:00:00Z"));
        http.server().verify();
    }

    @Test
    void recordsTheSnapshotIdAfterASuccessfulSync() {
        expectSnapshot("snap-1");
        expectItems(ITEMS_URL, "{\"items\":[],\"next\":null}");

        service.syncUser(user);

        assertThat(user.getLastSyncedSnapshotId()).isEqualTo("snap-1");
        verify(userRepository).save(user);
    }

    @Test
    void unchangedSnapshotSkipsFetchingTheTracks() {
        user.setLastSyncedSnapshotId("snap-1");
        expectSnapshot("snap-1");

        service.syncUser(user);

        http.server().verify();
        verify(trackSeenRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changedSnapshotFetchesTracksAgain() {
        user.setLastSyncedSnapshotId("old");
        expectSnapshot("new");
        expectItems(ITEMS_URL, "{\"items\":[%s],\"next\":null}".formatted(item("t1", "S", "A", "2024-05-01T10:00:00Z")));

        service.syncUser(user);

        verify(trackSeenRepository).save(any(TrackSeen.class));
        assertThat(user.getLastSyncedSnapshotId()).isEqualTo("new");
    }

    @Test
    void tracksAlreadySeenAreNotStoredAgain() {
        when(trackSeenRepository.existsByUserAndPlaylistIdAndSpotifyTrackId(user, "pl1", "t1")).thenReturn(true);
        expectSnapshot("s");
        expectItems(ITEMS_URL, "{\"items\":[%s,%s],\"next\":null}".formatted(
                item("t1", "Old", "A", "2024-05-01T10:00:00Z"), item("t2", "New", "B", "2024-05-02T10:00:00Z")));

        service.syncUser(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository).save(saved.capture());
        assertThat(saved.getValue().getSpotifyTrackId()).isEqualTo("t2");
    }

    @Test
    void entriesWithoutATrackOrTrackIdAreSkipped() {
        expectSnapshot("s");
        expectItems(ITEMS_URL, "{\"items\":[{\"added_at\":\"2024-05-01T10:00:00Z\",\"item\":null},%s,%s],\"next\":null}".formatted(
                item(null, "Local file", "A", "2024-05-01T10:00:00Z"), item("ok", "Real", "B", "2024-05-01T10:00:00Z")));

        service.syncUser(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository).save(saved.capture());
        assertThat(saved.getValue().getSpotifyTrackId()).isEqualTo("ok");
    }

    @Test
    void trackWithoutArtistsIsStoredWithANullArtist() {
        expectSnapshot("s");
        expectItems(ITEMS_URL, """
                {"items":[{"added_at":"2024-05-01T10:00:00Z","item":{"id":"t1","name":"Podcast ep","artists":[]}}],"next":null}""");

        service.syncUser(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository).save(saved.capture());
        assertThat(saved.getValue().getArtistName()).isNull();
    }

    @Test
    void followsPaginationUntilThereIsNoNextPage() {
        String page2 = "https://api.spotify.com/v1/playlists/pl1/items?offset=100&limit=100";
        expectSnapshot("s");
        expectItems(ITEMS_URL, "{\"items\":[%s],\"next\":\"%s\"}".formatted(item("t1", "S1", "A", "2024-05-01T10:00:00Z"), page2));
        expectItems(page2, "{\"items\":[%s],\"next\":null}".formatted(item("t2", "S2", "A", "2024-05-01T10:00:00Z")));

        service.syncUser(user);

        verify(trackSeenRepository, times(2)).save(any(TrackSeen.class));
        http.server().verify();
    }

    @Test
    void shortRetryAfterIsWaitedOutAndRetried() {
        http.server().expect(once(), requestTo(SNAPSHOT_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));
        expectSnapshot("s");
        expectItems(ITEMS_URL, "{\"items\":[%s],\"next\":null}".formatted(item("t1", "S", "A", "2024-05-01T10:00:00Z")));

        service.syncUser(user);

        verify(trackSeenRepository).save(any(TrackSeen.class));
        http.server().verify();
    }

    @Test
    void longRetryAfterFailsFastInsteadOfSleepingOnTheSchedulerThread() {
        http.server().expect(once(), requestTo(SNAPSHOT_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3600"));

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.syncUser(user)).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
    }

    @Test
    void nonRateLimitErrorsPropagate() {
        http.server().expect(once(), requestTo(SNAPSHOT_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.syncUser(user)).isInstanceOf(HttpClientErrorException.Forbidden.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void scheduledTickSyncsOnlyUsersWhoPickedAPlaylist() {
        User noPlaylist = TestData.user("u2");
        when(userRepository.findAll()).thenReturn(List.of(user, noPlaylist));
        user.setLastSyncedSnapshotId("same");
        expectSnapshot("same");

        service.syncPlaylists();

        http.server().verify();
    }

    @Test
    void scheduledTickKeepsGoingWhenOneUsersSyncFails() {
        User broken = TestData.user("broken");
        broken.setPlaylistId("bad");
        when(userRepository.findAll()).thenReturn(List.of(broken, user));
        http.server().expect(once(), requestTo("https://api.spotify.com/v1/playlists/bad?fields=snapshot_id"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        user.setLastSyncedSnapshotId("same");
        expectSnapshot("same");

        service.syncPlaylists();

        http.server().verify();
    }

    @Test
    void longRateLimitPausesTheNextScheduledTicks() {
        when(userRepository.findAll()).thenReturn(List.of(user));
        http.server().expect(once(), requestTo(SNAPSHOT_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"));

        service.syncPlaylists();
        service.syncPlaylists();

        verify(userRepository, times(1)).findAll();
        http.server().verify();
    }
}
