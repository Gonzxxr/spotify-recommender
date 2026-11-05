package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.auth.SessionStore;
import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controllers -> session/auth layer -> services -> JPA -> Postgres, driven through MockMvc.
 */
@AutoConfigureMockMvc
class WebApiIT extends AbstractApplicationIT {

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SessionStore sessionStore;

    private Cookie loggedInAs(User user) {
        return new Cookie(SessionStore.COOKIE_NAME, sessionStore.create(user.getSpotifyUserId()));
    }

    private User savedUser(String spotifyId) {
        return users.save(TestData.user(spotifyId));
    }

    private void spotifyAnswersLogin(String accessToken, String spotifyId, String displayName) {
        externalApis.server().expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"%s\",\"refresh_token\":\"refresh-%s\",\"expires_in\":3600}"
                        .formatted(accessToken, spotifyId), MediaType.APPLICATION_JSON));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me"))
                .andExpect(header("Authorization", "Bearer " + accessToken))
                .andRespond(withSuccess("{\"id\":\"%s\",\"display_name\":\"%s\"}".formatted(spotifyId, displayName), MediaType.APPLICATION_JSON));
    }

    private String stateFromLogin() throws Exception {
        String location = mvc.perform(get("/auth/spotify/login")).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
        return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
    }

    // ---- auth ----

    @Test
    void loginRedirectsToSpotifyWithTheConfiguredApp() throws Exception {
        String location = mvc.perform(get("/auth/spotify/login")).andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(location).startsWith("https://accounts.spotify.com/authorize");
        assertThat(params.getFirst("client_id")).isEqualTo("test-client-id");
        assertThat(params.getFirst("scope")).isEqualTo("playlist-read-private");
        assertThat(params.getFirst("state")).isNotBlank();
    }

    @Test
    void completeLoginPersistsTheUserEncryptsTokensAndStartsASession() throws Exception {
        String state = stateFromLogin();
        spotifyAnswersLogin("access-abc", "spotify-1", "Gonza");

        MvcResult callback = mvc.perform(get("/auth/spotify/callback").param("code", "the-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?connected=true&displayName=Gonza"))
                .andReturn();

        User stored = users.findBySpotifyUserId("spotify-1").orElseThrow();
        assertThat(stored.getAccessToken()).isEqualTo("access-abc");
        assertThat(stored.getRefreshToken()).isEqualTo("refresh-spotify-1");
        String rawToken = jdbc.queryForObject("select access_token from users where spotify_user_id = 'spotify-1'", String.class);
        assertThat(rawToken).doesNotContain("access-abc");

        String setCookie = callback.getResponse().getHeader("Set-Cookie");
        String sessionId = setCookie.substring("SESSION_ID=".length(), setCookie.indexOf(';'));
        mvc.perform(get("/auth/spotify/me").cookie(new Cookie("SESSION_ID", sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotifyUserId").value("spotify-1"))
                .andExpect(jsonPath("$.displayName").value("Gonza"));
        externalApis.server().verify();
    }

    @Test
    void loggingInAgainUpdatesTheSameUserInsteadOfCreatingAnother() throws Exception {
        spotifyAnswersLogin("first", "spotify-1", "Old Name");
        mvc.perform(get("/auth/spotify/callback").param("code", "c1").param("state", stateFromLogin())).andExpect(status().isFound());
        externalApis.server().reset();
        spotifyAnswersLogin("second", "spotify-1", "New Name");
        mvc.perform(get("/auth/spotify/callback").param("code", "c2").param("state", stateFromLogin())).andExpect(status().isFound());

        assertThat(users.count()).isEqualTo(1);
        User stored = users.findBySpotifyUserId("spotify-1").orElseThrow();
        assertThat(stored.getAccessToken()).isEqualTo("second");
        assertThat(stored.getDisplayName()).isEqualTo("New Name");
    }

    @Test
    void callbackWithAForgedStateCreatesNothingAndNeverCallsSpotify() throws Exception {
        mvc.perform(get("/auth/spotify/callback").param("code", "c").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state"));

        assertThat(users.count()).isZero();
        externalApis.server().verify();
    }

    @Test
    void spotifyRejectingTheCodeLeavesNoUserAndNoSession() throws Exception {
        String state = stateFromLogin();
        externalApis.server().expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        mvc.perform(get("/auth/spotify/callback").param("code", "expired-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=spotify_unavailable"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(users.count()).isZero();
    }

    @Test
    void cancellingOnSpotifysConsentScreenReturnsToTheAppCleanly() throws Exception {
        String state = stateFromLogin();

        mvc.perform(get("/auth/spotify/callback").param("error", "access_denied").param("state", state))
                .andExpect(header().string("Location", "/?error=access_denied"));

        assertThat(users.count()).isZero();
        externalApis.server().verify();
    }

    @Test
    void meRejectsMissingForgedAndOrphanedSessions() throws Exception {
        mvc.perform(get("/auth/spotify/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/spotify/me").cookie(new Cookie("SESSION_ID", "forged"))).andExpect(status().isUnauthorized());

        Cookie orphan = loggedInAs(savedUser("spotify-1"));
        users.deleteAll();
        mvc.perform(get("/auth/spotify/me").cookie(orphan)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie session = loggedInAs(savedUser("spotify-1"));
        mvc.perform(get("/auth/spotify/me").cookie(session)).andExpect(status().isOk());

        mvc.perform(post("/auth/spotify/logout").cookie(session)).andExpect(status().isOk());

        mvc.perform(get("/auth/spotify/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    // ---- playlists ----

    @Test
    void playlistsListsOnlyTheOnesTheUserOwns() throws Exception {
        Cookie session = loggedInAs(savedUser("spotify-1"));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andExpect(header("Authorization", "Bearer access-spotify-1"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"mine","name":"Mine","owner":{"id":"spotify-1"},"images":[{"url":"http://img"}],"items":{"total":7}},
                          {"id":"theirs","name":"Theirs","owner":{"id":"other"},"images":[],"items":{"total":1}}]}""",
                        MediaType.APPLICATION_JSON));

        mvc.perform(get("/playlists").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("mine"))
                .andExpect(jsonPath("$[0].trackCount").value(7));
    }

    @Test
    void playlistsRequiresALogin() throws Exception {
        mvc.perform(get("/playlists")).andExpect(status().isUnauthorized());
    }

    @Test
    void playlistsTranslatesSpotifyFailuresIntoStatusCodes() throws Exception {
        Cookie session = loggedInAs(savedUser("spotify-1"));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        mvc.perform(get("/playlists").cookie(session)).andExpect(status().isTooManyRequests());

        externalApis.server().reset();
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        mvc.perform(get("/playlists").cookie(session)).andExpect(status().isBadGateway());
    }

    @Test
    void expiredTokenIsRefreshedTransparentlyWhenListingPlaylists() throws Exception {
        User user = TestData.user("spotify-1");
        user.setTokenExpiresAt(Instant.now().minusSeconds(5));
        Cookie session = loggedInAs(users.save(user));
        externalApis.server().expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"renewed\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andExpect(header("Authorization", "Bearer renewed"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        mvc.perform(get("/playlists").cookie(session)).andExpect(status().isOk());

        assertThat(users.findBySpotifyUserId("spotify-1").orElseThrow().getAccessToken()).isEqualTo("renewed");
        externalApis.server().verify();
    }

    @Test
    void selectingAPlaylistSyncsItsTracksStoresTheChoiceAndStartsRecommending() throws Exception {
        Cookie session = loggedInAs(savedUser("spotify-1"));
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/pl9?fields=snapshot_id"))
                .andRespond(withSuccess("{\"snapshot_id\":\"snap\"}", MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/pl9/items"))
                .andRespond(withSuccess("""
                        {"items":[{"added_at":"2024-05-01T10:00:00Z","item":{"id":"t1","name":"Creep","artists":[{"name":"Radiohead"}]}}],"next":null}""",
                        MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=track.getsimilar")))
                .andRespond(withSuccess("{\"similartracks\":{\"track\":[]}}", MediaType.APPLICATION_JSON));

        mvc.perform(post("/playlists/select").cookie(session).param("playlistId", "pl9")).andExpect(status().isOk());

        User stored = users.findBySpotifyUserId("spotify-1").orElseThrow();
        assertThat(stored.getPlaylistId()).isEqualTo("pl9");
        assertThat(stored.getLastSyncedSnapshotId()).isEqualTo("snap");
        assertThat(trackSeen.findAll()).singleElement().satisfies(t -> {
            assertThat(t.getTrackName()).isEqualTo("Creep");
            assertThat(t.getPlaylistId()).isEqualTo("pl9");
        });
        // the async recommendation burst kicked off by the select
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(trackSeen.findAll().get(0).getRecommendationAttempts()).isEqualTo(1));
    }

    @Test
    void selectingAPlaylistSpotifyForbidsKeepsThePreviousChoice() throws Exception {
        User user = TestData.user("spotify-1");
        user.setPlaylistId("previous");
        Cookie session = loggedInAs(users.save(user));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/playlists/private?fields=snapshot_id"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        mvc.perform(post("/playlists/select").cookie(session).param("playlistId", "private")).andExpect(status().isForbidden());

        assertThat(users.findBySpotifyUserId("spotify-1").orElseThrow().getPlaylistId()).isEqualTo("previous");
        assertThat(trackSeen.count()).isZero();
    }

    @Test
    void selectRequiresALogin() throws Exception {
        mvc.perform(post("/playlists/select").param("playlistId", "pl")).andExpect(status().isUnauthorized());
    }

    // ---- recommendations ----

    private Recommendation seedRecommendation(User user, TrackSeen source, String trackId, double score) {
        return recommendations.save(TestData.recommendation(user, source, trackId, score));
    }

    @Test
    void recommendationsAreNotFoundUntilAPlaylistIsSelected() throws Exception {
        mvc.perform(get("/recommendations").cookie(loggedInAs(savedUser("spotify-1")))).andExpect(status().isNotFound());
    }

    @Test
    void recommendationsRequireALogin() throws Exception {
        mvc.perform(get("/recommendations")).andExpect(status().isUnauthorized());
    }

    @Test
    void recommendationsComeBackBestFirstAndRotateOutForAnHourOnceShown() throws Exception {
        User user = TestData.user("spotify-1");
        user.setPlaylistId("pl");
        user = users.save(user);
        TrackSeen source = trackSeen.save(TestData.trackSeen(user, "pl", "src", "Source Song", "Source Artist"));
        seedRecommendation(user, source, "low", 0.2);
        seedRecommendation(user, source, "high", 0.9);
        Cookie session = loggedInAs(user);

        mvc.perform(get("/recommendations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].recommendedTrackId").value("high"))
                .andExpect(jsonPath("$[1].recommendedTrackId").value("low"))
                .andExpect(jsonPath("$[0].sourceTrackName").value("Source Song"))
                .andExpect(jsonPath("$[0].sourceArtistName").value("Source Artist"));

        assertThat(recommendations.findAll()).allSatisfy(r -> assertThat(r.getShownAt()).isNotNull());
        mvc.perform(get("/recommendations").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void recommendationShownMoreThanAnHourAgoComesBack() throws Exception {
        User user = TestData.user("spotify-1");
        user.setPlaylistId("pl");
        user = users.save(user);
        TrackSeen source = trackSeen.save(TestData.trackSeen(user, "pl", "src", "S", "A"));
        Recommendation r = TestData.recommendation(user, source, "again", 0.5);
        r.setShownAt(Instant.now().minus(Duration.ofHours(2)));
        recommendations.save(r);

        mvc.perform(get("/recommendations").cookie(loggedInAs(user)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].recommendedTrackId").value("again"));
    }

    @Test
    void usersNeverSeeEachOthersRecommendations() throws Exception {
        User alice = TestData.user("alice");
        alice.setPlaylistId("pl");
        alice = users.save(alice);
        User bob = TestData.user("bob");
        bob.setPlaylistId("pl");
        bob = users.save(bob);
        TrackSeen aliceSource = trackSeen.save(TestData.trackSeen(alice, "pl", "src", "S", "A"));
        seedRecommendation(alice, aliceSource, "alices-rec", 0.9);

        mvc.perform(get("/recommendations").cookie(loggedInAs(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/recommendations").cookie(loggedInAs(alice)))
                .andExpect(jsonPath("$[0].recommendedTrackId").value("alices-rec"));
    }

    // ---- static frontend ----

    @Test
    void servesTheSinglePageFrontend() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Spotify Recommender</title>")));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/style.css")).andExpect(status().isOk());
    }
}
