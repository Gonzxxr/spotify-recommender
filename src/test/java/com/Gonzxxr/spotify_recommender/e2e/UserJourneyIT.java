package com.Gonzxxr.spotify_recommender.e2e;

import com.Gonzxxr.spotify_recommender.integration.ExternalApisTestConfig;
import com.Gonzxxr.spotify_recommender.integration.PostgresTestContainer;
import com.Gonzxxr.spotify_recommender.recommendation.RecommendationService;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.MockHttp;
import com.Gonzxxr.spotify_recommender.sync.PlaylistSyncService;
import com.Gonzxxr.spotify_recommender.sync.SpotifySearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The whole product through its public HTTP surface: a real embedded server on a random port, a real
 * HTTP client, real Postgres, and the real Spring wiring from controller to repository. The only thing
 * faked is the outside world (Spotify and Last.fm), simulated at the HTTP level.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ExternalApisTestConfig.class)
class UserJourneyIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;
    @Autowired
    private MockHttp externalApis;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private TrackSeenRepository trackSeen;
    @Autowired
    private RecommendationRepository recommendations;
    @Autowired
    private PlaylistSyncService syncService;
    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private SpotifySearchService searchService;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
    }

    @AfterEach
    void leaveTheDatabaseEmpty() {
        jdbc.execute("TRUNCATE TABLE recommendation, track_seen, users RESTART IDENTITY CASCADE");
    }

    @BeforeEach
    void cleanSlate() {
        externalApis.server().reset();
        jdbc.execute("TRUNCATE TABLE recommendation, track_seen, users RESTART IDENTITY CASCADE");
        Object search = AopProxyUtils.getSingletonTarget(searchService) != null ? AopProxyUtils.getSingletonTarget(searchService) : searchService;
        ((Map<?, ?>) ReflectionTestUtils.getField(search, "cache")).clear();
        ReflectionTestUtils.setField(search, "blockedUntil", Instant.EPOCH);
        Object sync = AopProxyUtils.getSingletonTarget(syncService) != null ? AopProxyUtils.getSingletonTarget(syncService) : syncService;
        ReflectionTestUtils.setField(sync, "blockedUntil", Instant.EPOCH);
    }

    // ---- tiny HTTP helpers ----

    private record Reply(int status, java.net.http.HttpHeaders headers, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private Reply send(String method, String path, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (sessionCookie != null) request.header("Cookie", "SESSION_ID=" + sessionCookie);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers(), response.body());
    }

    private Reply get(String path, String session) throws Exception {
        return send("GET", path, session);
    }

    private Reply post(String path, String session) throws Exception {
        return send("POST", path, session);
    }

    private static String sessionIdFrom(Reply callback) {
        String setCookie = callback.header("Set-Cookie");
        return setCookie.substring("SESSION_ID=".length(), setCookie.indexOf(';'));
    }

    // ---- simulated outside world ----

    private void spotifyAuthorizesUser(String spotifyId, String displayName) {
        externalApis.server().expect(once(), requestTo("https://accounts.spotify.com/api/token"))
                .andRespond(withSuccess("{\"access_token\":\"access-%s\",\"refresh_token\":\"refresh-%s\",\"expires_in\":3600}"
                        .formatted(spotifyId, spotifyId), MediaType.APPLICATION_JSON));
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me"))
                .andRespond(withSuccess("{\"id\":\"%s\",\"display_name\":\"%s\"}".formatted(spotifyId, displayName), MediaType.APPLICATION_JSON));
    }

    /** Runs the real login redirect, then plays Spotify's part (consent + redirect back) and returns the session id. */
    private String logIn(String spotifyId, String displayName) throws Exception {
        Reply login = get("/auth/spotify/login", null);
        assertThat(login.status()).isEqualTo(302);
        String state = UriComponentsBuilder.fromUriString(login.header("Location")).build().getQueryParams().getFirst("state");
        spotifyAuthorizesUser(spotifyId, displayName);
        Reply callback = get("/auth/spotify/callback?code=code-" + spotifyId + "&state=" + state, null);
        assertThat(callback.status()).isEqualTo(302);
        return sessionIdFrom(callback);
    }

    private void playlistWithThreeTracks(String snapshot) {
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/chill?fields=snapshot_id"))
                .andRespond(withSuccess("{\"snapshot_id\":\"%s\"}".formatted(snapshot), MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/chill/items"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"added_at":"2024-05-01T10:00:00Z","item":{"id":"t1","name":"Creep","artists":[{"name":"Radiohead"}]}},
                          {"added_at":"2024-05-02T10:00:00Z","item":{"id":"t2","name":"Karma Police","artists":[{"name":"Radiohead"}]}},
                          {"added_at":"2024-05-03T10:00:00Z","item":{"id":"t3","name":"No Surprises","artists":[{"name":"Radiohead"}]}}
                        ],"next":null}""", MediaType.APPLICATION_JSON));
    }

    private void lastFmKnowsSimilarSongs() {
        similar("Creep", "Fake Plastic Trees", 0.9, "Weak Filler", 0.05);
        similar("Karma%20Police", "Paranoid Android", 0.8, null, 0);
        similar("No%20Surprises", "Exit Music", 0.7, null, 0);
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=artist.gettoptags")))
                .andRespond(withSuccess("{\"toptags\":{\"tag\":[{\"name\":\"alternative\"}]}}", MediaType.APPLICATION_JSON));
        search("Fake%20Plastic%20Trees", "spot-fpt");
        search("Paranoid%20Android", "spot-pa");
        search("Exit%20Music", "spot-em");
    }

    private void similar(String forTrack, String name, double match, String second, double secondMatch) {
        String tracks = "{\"name\":\"%s\",\"match\":\"%s\",\"artist\":{\"name\":\"Radiohead\"}}".formatted(name, match);
        if (second != null) {
            tracks += ",{\"name\":\"%s\",\"match\":\"%s\",\"artist\":{\"name\":\"Someone\"}}".formatted(second, secondMatch);
        }
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=track.getsimilar")))
                .andExpect(requestTo(containsString("track=" + forTrack + "&")))
                .andRespond(withSuccess("{\"similartracks\":{\"track\":[%s]}}".formatted(tracks), MediaType.APPLICATION_JSON));
    }

    private void search(String encodedTrack, String spotifyId) {
        externalApis.server().expect(manyTimes(), requestTo(containsString("/v1/search")))
                .andExpect(requestTo(containsString("track:" + encodedTrack)))
                .andRespond(withSuccess("""
                        {"tracks":{"items":[{"id":"%s","album":{"images":[{"url":"http://cover/%s"}]}}]}}""".formatted(spotifyId, spotifyId),
                        MediaType.APPLICATION_JSON));
    }

    // ---- journeys ----

    @Test
    void aNewListenerConnectsPicksAPlaylistAndGetsRecommendations() throws Exception {
        // the page loads and the visitor is not logged in yet
        Reply home = get("/", null);
        assertThat(home.status()).isEqualTo(200);
        assertThat(home.body()).contains("<title>Spotify Recommender</title>");
        assertThat(get("/auth/spotify/me", null).status()).isEqualTo(401);

        // login through Spotify's OAuth redirect and come back with a session cookie
        Reply login = get("/auth/spotify/login", null);
        assertThat(login.header("Location")).startsWith("https://accounts.spotify.com/authorize?");
        String state = UriComponentsBuilder.fromUriString(login.header("Location")).build().getQueryParams().getFirst("state");
        spotifyAuthorizesUser("spotify-1", "Gonza");
        Reply callback = get("/auth/spotify/callback?code=abc&state=" + state, null);
        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).isEqualTo("/?connected=true&displayName=Gonza");
        assertThat(callback.header("Set-Cookie")).contains("HttpOnly", "SameSite=Lax");
        String session = sessionIdFrom(callback);

        Reply me = get("/auth/spotify/me", session);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("spotifyUserId").asString()).isEqualTo("spotify-1");

        // nothing to recommend until a playlist is chosen
        assertThat(get("/recommendations", session).status()).isEqualTo(404);

        // list playlists: only the ones the user owns
        externalApis.server().reset();
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"chill","name":"Chill","owner":{"id":"spotify-1"},"images":[{"url":"http://img"}],"items":{"total":3}},
                          {"id":"not-mine","name":"Followed","owner":{"id":"somebody"},"images":[],"items":{"total":9}}]}""",
                        MediaType.APPLICATION_JSON));
        Reply playlists = get("/playlists", session);
        assertThat(playlists.status()).isEqualTo(200);
        assertThat(playlists.json()).hasSize(1);
        assertThat(playlists.json().get(0).get("name").asString()).isEqualTo("Chill");

        // choose one: its tracks get synced and recommendations start generating in the background
        externalApis.server().reset();
        playlistWithThreeTracks("snap-1");
        lastFmKnowsSimilarSongs();
        assertThat(post("/playlists/select?playlistId=chill", session).status()).isEqualTo(200);
        assertThat(trackSeen.count()).isEqualTo(3);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recommendations.count()).isEqualTo(3));

        // the feed: best match first, enriched with genre, cover art and the song that inspired it
        Reply feed = get("/recommendations", session);
        assertThat(feed.status()).isEqualTo(200);
        JsonNode items = feed.json();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("recommendedTrackName").asString()).isEqualTo("Fake Plastic Trees");
        assertThat(items.get(0).get("matchScore").asDouble()).isEqualTo(0.9);
        assertThat(items.get(0).get("genre").asString()).isEqualTo("alternative");
        assertThat(items.get(0).get("albumImageUrl").asString()).isEqualTo("http://cover/spot-fpt");
        assertThat(items.get(0).get("sourceTrackName").asString()).isEqualTo("Creep");
        assertThat(items.get(1).get("recommendedTrackName").asString()).isEqualTo("Paranoid Android");
        assertThat(items.get(2).get("recommendedTrackName").asString()).isEqualTo("Exit Music");
        // the weak candidate (match 0.05) never made it
        assertThat(feed.body()).doesNotContain("Weak Filler");

        // already shown, so the same ones do not come straight back
        assertThat(get("/recommendations", session).json()).isEmpty();

        // the person adds a song later; the background jobs pick it up on their next ticks
        externalApis.server().reset();
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/chill?fields=snapshot_id"))
                .andRespond(withSuccess("{\"snapshot_id\":\"snap-2\"}", MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo("https://api.spotify.com/v1/playlists/chill/items"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"added_at":"2024-05-01T10:00:00Z","item":{"id":"t1","name":"Creep","artists":[{"name":"Radiohead"}]}},
                          {"added_at":"2024-06-01T10:00:00Z","item":{"id":"t4","name":"Lucky","artists":[{"name":"Radiohead"}]}}],"next":null}""",
                        MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=track.getsimilar")))
                .andExpect(requestTo(containsString("track=Lucky&")))
                .andRespond(withSuccess("{\"similartracks\":{\"track\":[{\"name\":\"Let Down\",\"match\":\"0.6\",\"artist\":{\"name\":\"Radiohead\"}}]}}",
                        MediaType.APPLICATION_JSON));
        externalApis.server().expect(manyTimes(), requestTo(containsString("method=artist.gettoptags")))
                .andRespond(withSuccess("{\"toptags\":{\"tag\":[{\"name\":\"art rock\"}]}}", MediaType.APPLICATION_JSON));
        search("Let%20Down", "spot-ld");
        syncService.syncPlaylists();
        recommendationService.generateRecommendations();

        Reply later = get("/recommendations", session);
        assertThat(later.json()).hasSize(1);
        assertThat(later.json().get(0).get("recommendedTrackName").asString()).isEqualTo("Let Down");
        assertThat(later.json().get(0).get("sourceTrackName").asString()).isEqualTo("Lucky");

        // logging out kills the session on the server, not just the cookie
        Reply logout = post("/auth/spotify/logout", session);
        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.header("Set-Cookie")).contains("Max-Age=0");
        assertThat(get("/auth/spotify/me", session).status()).isEqualTo(401);
        assertThat(get("/recommendations", session).status()).isEqualTo(401);
    }

    @Test
    void twoFriendsOnTheSameInstanceDoNotSeeEachOthersData() throws Exception {
        String alice = logIn("alice", "Alice");
        externalApis.server().reset();
        String bob = logIn("bob", "Bob");
        externalApis.server().reset();

        playlistWithThreeTracks("snap-1");
        lastFmKnowsSimilarSongs();
        assertThat(post("/playlists/select?playlistId=chill", alice).status()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recommendations.count()).isEqualTo(3));

        assertThat(get("/recommendations", alice).json()).hasSize(3);
        // Bob never picked a playlist, so for him there is simply nothing
        assertThat(get("/recommendations", bob).status()).isEqualTo(404);
        assertThat(get("/auth/spotify/me", bob).json().get("spotifyUserId").asString()).isEqualTo("bob");
        assertThat(users.count()).isEqualTo(2);
    }

    @Test
    void everyProtectedEndpointRejectsAnonymousAndForgedSessions() throws Exception {
        for (String forged : new String[]{null, "not-a-real-session"}) {
            assertThat(get("/auth/spotify/me", forged).status()).isEqualTo(401);
            assertThat(get("/playlists", forged).status()).isEqualTo(401);
            assertThat(post("/playlists/select?playlistId=x", forged).status()).isEqualTo(401);
            assertThat(get("/recommendations", forged).status()).isEqualTo(401);
        }
        assertThat(trackSeen.count()).isZero();
    }

    @Test
    void aForgedOAuthStateCannotLogAnyoneIn() throws Exception {
        Reply callback = get("/auth/spotify/callback?code=stolen&state=made-up", null);

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).isEqualTo("/?error=invalid_state");
        assertThat(callback.header("Set-Cookie")).isNull();
        assertThat(users.count()).isZero();
    }

    @Test
    void spotifyRateLimitsSurfaceAsRetryableErrorsAndDoNotBreakTheSession() throws Exception {
        String session = logIn("spotify-1", "Gonza");
        externalApis.server().reset();
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/me/playlists?limit=50"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(get("/playlists", session).status()).isEqualTo(429);

        assertThat(get("/auth/spotify/me", session).status()).isEqualTo(200);
    }

    @Test
    void aPrivatePlaylistSpotifyRefusesToShareDoesNotReplaceTheCurrentChoice() throws Exception {
        String session = logIn("spotify-1", "Gonza");
        externalApis.server().reset();
        playlistWithThreeTracks("snap-1");
        lastFmKnowsSimilarSongs();
        assertThat(post("/playlists/select?playlistId=chill", session).status()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(recommendations.count()).isEqualTo(3));

        externalApis.server().reset();
        externalApis.server().expect(once(), requestTo("https://api.spotify.com/v1/playlists/secret?fields=snapshot_id"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(post("/playlists/select?playlistId=secret", session).status()).isEqualTo(403);

        assertThat(users.findBySpotifyUserId("spotify-1").orElseThrow().getPlaylistId()).isEqualTo("chill");
    }
}
