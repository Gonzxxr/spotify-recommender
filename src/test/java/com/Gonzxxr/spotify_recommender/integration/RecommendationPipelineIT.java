package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Sync -> TrackSeen queue -> recommendation job, all with real services and a real database;
 * only the HTTP edge to Spotify/Last.fm is simulated.
 */
class RecommendationPipelineIT extends AbstractApplicationIT {

    private static final String SNAPSHOT_URL = "https://api.spotify.com/v1/playlists/pl1?fields=snapshot_id";
    private static final String ITEMS_URL = "https://api.spotify.com/v1/playlists/pl1/items";

    private User user;

    @BeforeEach
    void seedUser() {
        user = TestData.user("spotify-1");
        user.setPlaylistId("pl1");
        user = users.save(user);
    }

    private void spotifyPlaylistHasTracks(String snapshot, String... trackJson) {
        externalApis.server().expect(manyTimes(), requestTo(SNAPSHOT_URL))
                .andRespond(withSuccess("{\"snapshot_id\":\"%s\"}".formatted(snapshot), MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo(ITEMS_URL))
                .andRespond(withSuccess("{\"items\":[%s],\"next\":null}".formatted(String.join(",", trackJson)), MediaType.APPLICATION_JSON));
    }

    private static String playlistItem(String id, String name, String artist) {
        return """
                {"added_at":"2024-05-01T10:00:00Z","item":{"id":"%s","name":"%s","artists":[{"name":"%s"}]}}"""
                .formatted(id, name, artist);
    }

    private void lastFmSimilar(String forTrack, String body) {
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=track.getsimilar")))
                .andExpect(requestTo(containsString("track=" + forTrack)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void lastFmTag(String tag) {
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=artist.gettoptags")))
                .andRespond(withSuccess("{\"toptags\":{\"tag\":[{\"name\":\"%s\"}]}}".formatted(tag), MediaType.APPLICATION_JSON));
    }

    private void spotifySearchFinds(String trackEncodedFragment, String id) {
        externalApis.server().expect(manyTimes(), requestTo(containsString("/v1/search")))
                .andExpect(requestTo(containsString(trackEncodedFragment)))
                .andRespond(withSuccess("""
                        {"tracks":{"items":[{"id":"%s","album":{"images":[{"url":"http://cover/%s"}]}}]}}""".formatted(id, id),
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void syncPersistsTheTracksAndTheSnapshot() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"), playlistItem("t2", "Karma Police", "Radiohead"));

        syncService.syncUser(user);

        List<TrackSeen> stored = trackSeen.findAll();
        assertThat(stored).extracting(TrackSeen::getSpotifyTrackId).containsExactlyInAnyOrder("t1", "t2");
        assertThat(stored).allSatisfy(t -> {
            assertThat(t.getPlaylistId()).isEqualTo("pl1");
            assertThat(t.getRecommendationAttempts()).isZero();
        });
        assertThat(users.findById(user.getUserId()).orElseThrow().getLastSyncedSnapshotId()).isEqualTo("snap-1");
    }

    @Test
    void syncingTwiceDoesNotDuplicateTracks() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));

        syncService.syncUser(user);
        user.setLastSyncedSnapshotId(null); // force the second pass to re-read the items
        syncService.syncUser(user);

        assertThat(trackSeen.count()).isEqualTo(1);
    }

    @Test
    void scheduledSyncTickPicksUpUsersFromTheDatabase() {
        User noPlaylist = users.save(TestData.user("spotify-2"));
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));

        syncService.syncPlaylists();

        assertThat(trackSeen.findAll()).hasSize(1).allSatisfy(t -> assertThat(t.getUser().getUserId()).isEqualTo(user.getUserId()));
        assertThat(users.findById(noPlaylist.getUserId()).orElseThrow().getLastSyncedSnapshotId()).isNull();
    }

    @Test
    void schedulerTurnsSyncedTracksIntoRecommendations() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        lastFmSimilar("Creep", """
                {"similartracks":{"track":[
                  {"name":"Fake Plastic Trees","match":"0.9","artist":{"name":"Radiohead"}},
                  {"name":"Too Weak","match":"0.01","artist":{"name":"Nobody"}}]}}""");
        lastFmTag("alternative");
        spotifySearchFinds("Fake%20Plastic%20Trees", "spot-fpt");
        syncService.syncUser(user);

        recommendationService.generateRecommendations();

        List<Recommendation> saved = recommendations.findAll();
        assertThat(saved).hasSize(1);
        Recommendation r = saved.get(0);
        assertThat(r.getRecommendedTrackId()).isEqualTo("spot-fpt");
        assertThat(r.getRecommendedTrackName()).isEqualTo("Fake Plastic Trees");
        assertThat(r.getAlbumImageUrl()).isEqualTo("http://cover/spot-fpt");
        assertThat(r.getGenre()).isEqualTo("alternative");
        assertThat(r.getMatchScore()).isEqualTo(0.9);
        assertThat(r.getSourceTrack().getTrackSeenId()).isEqualTo(trackSeen.findAll().get(0).getTrackSeenId());
        assertThat(r.getPlaylistId()).isEqualTo("pl1");
        assertThat(r.getShownAt()).isNull();
    }

    @Test
    void processedTrackIsStampedAndNotRetriedWithinTheCooldown() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        lastFmSimilar("Creep", "{\"similartracks\":{\"track\":[]}}");
        syncService.syncUser(user);

        recommendationService.generateRecommendations();
        recommendationService.generateRecommendations();

        TrackSeen processed = trackSeen.findAll().get(0);
        assertThat(processed.getRecommendationAttempts()).isEqualTo(1);
        assertThat(processed.getLastAttemptedAt()).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void candidatesAlreadyInThePlaylistAreNotRecommended() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"), playlistItem("t2", "Karma Police", "Radiohead"));
        lastFmSimilar("Creep", """
                {"similartracks":{"track":[{"name":"Karma Police","match":"0.95","artist":{"name":"radiohead"}}]}}""");
        lastFmSimilar("Karma%20Police", "{\"similartracks\":{\"track\":[]}}");
        lastFmTag("alternative");
        syncService.syncUser(user);

        recommendationService.generateRecommendations();

        assertThat(recommendations.findAll()).isEmpty();
    }

    @Test
    void unfindableSpotifyTracksAreSkippedWithoutFailingTheRun() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        lastFmSimilar("Creep", """
                {"similartracks":{"track":[{"name":"Ghost","match":"0.9","artist":{"name":"Nobody"}}]}}""");
        lastFmTag("rock");
        externalApis.server().expect(manyTimes(), requestTo(containsString("/v1/search")))
                .andRespond(withSuccess("{\"tracks\":{\"items\":[]}}", MediaType.APPLICATION_JSON));
        syncService.syncUser(user);

        recommendationService.generateRecommendations();

        assertThat(recommendations.findAll()).isEmpty();
    }

    @Test
    void lastFmOutageLeavesTheTrackForALaterRetry() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=track.getsimilar")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        syncService.syncUser(user);

        recommendationService.generateRecommendations();

        assertThat(recommendations.count()).isZero();
        assertThat(trackSeen.findAll().get(0).getRecommendationAttempts()).isEqualTo(1);
    }

    @Test
    void expiredTokenIsRefreshedAndPersistedBeforeSearching() {
        user.setTokenExpiresAt(Instant.now().minusSeconds(60));
        user.setRefreshToken("old-refresh");
        users.save(user);
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        externalApis.server().expect(manyTimes(), requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withSuccess("{\"access_token\":\"fresh-access\",\"refresh_token\":\"fresh-refresh\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        syncService.syncUser(users.findById(user.getUserId()).orElseThrow());

        User reloaded = users.findById(user.getUserId()).orElseThrow();
        assertThat(reloaded.getAccessToken()).isEqualTo("fresh-access");
        assertThat(reloaded.getRefreshToken()).isEqualTo("fresh-refresh");
        assertThat(reloaded.getTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3000));
    }

    @Test
    void selectingAPlaylistTriggersTheAsyncRecommendationBurst() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        lastFmSimilar("Creep", "{\"similartracks\":{\"track\":[]}}");
        syncService.syncUser(user);
        User reloaded = users.findById(user.getUserId()).orElseThrow();

        recommendationService.generateForUserAsync(reloaded);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(trackSeen.findAll().get(0).getRecommendationAttempts()).isEqualTo(1));
    }

    @Test
    void searchServiceRateLimitShortCircuitsLaterCandidatesInTheSameRun() {
        spotifyPlaylistHasTracks("snap-1", playlistItem("t1", "Creep", "Radiohead"));
        lastFmSimilar("Creep", """
                {"similartracks":{"track":[
                  {"name":"First","match":"0.9","artist":{"name":"A"}},
                  {"name":"Second","match":"0.9","artist":{"name":"B"}}]}}""");
        lastFmTag("rock");
        externalApis.server().expect(once(), requestTo(containsString("/v1/search")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "600"));
        syncService.syncUser(user);

        recommendationService.generateRecommendations();

        assertThat(recommendations.count()).isZero();
        externalApis.server().verify();
    }
}
