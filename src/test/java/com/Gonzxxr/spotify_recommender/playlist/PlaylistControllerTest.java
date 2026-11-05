package com.Gonzxxr.spotify_recommender.playlist;

import com.Gonzxxr.spotify_recommender.auth.CurrentUserResolver;
import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.recommendation.RecommendationService;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import com.Gonzxxr.spotify_recommender.sync.PlaylistSyncService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaylistControllerTest {

    private static final Cookie SESSION = new Cookie("SESSION_ID", "sid");

    @Mock
    private UserRepository userRepository;
    @Mock
    private CurrentUserResolver resolver;
    @Mock
    private SpotifyAuthService authService;
    @Mock
    private PlaylistService playlistService;
    @Mock
    private PlaylistSyncService syncService;
    @Mock
    private RecommendationService recommendationService;

    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PlaylistController(
                userRepository, resolver, authService, playlistService, syncService, recommendationService)).build();
        user = TestData.user("spotify-1");
        when(resolver.resolve("sid")).thenReturn(Optional.of(user));
        when(resolver.resolve(null)).thenReturn(Optional.empty());
        when(authService.getValidAccessToken(user)).thenReturn("tok");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static HttpClientErrorException spotifyError(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(), new byte[0], null);
    }

    @Test
    void listRequiresALogin() throws Exception {
        mvc.perform(get("/playlists")).andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    @Test
    void listReturnsTheUsersPlaylistsUsingAFreshToken() throws Exception {
        when(playlistService.fetchUserPlaylists("tok", "spotify-1"))
                .thenReturn(List.of(new PlaylistSummary("p1", "Mine", "http://img", 12)));

        mvc.perform(get("/playlists").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].name").value("Mine"))
                .andExpect(jsonPath("$[0].imageUrl").value("http://img"))
                .andExpect(jsonPath("$[0].trackCount").value(12));
    }

    @Test
    void listMapsSpotifyRateLimitTo429() throws Exception {
        when(playlistService.fetchUserPlaylists("tok", "spotify-1")).thenThrow(spotifyError(HttpStatus.TOO_MANY_REQUESTS));

        mvc.perform(get("/playlists").cookie(SESSION)).andExpect(status().isTooManyRequests());
    }

    @Test
    void listMapsOtherSpotifyFailuresTo502() throws Exception {
        when(playlistService.fetchUserPlaylists("tok", "spotify-1")).thenThrow(new ResourceAccessException("timeout"));
        mvc.perform(get("/playlists").cookie(SESSION)).andExpect(status().isBadGateway());

        doThrow(spotifyError(HttpStatus.INTERNAL_SERVER_ERROR)).when(playlistService).fetchUserPlaylists("tok", "spotify-1");
        mvc.perform(get("/playlists").cookie(SESSION)).andExpect(status().isBadGateway());
    }

    @Test
    void selectRequiresALogin() throws Exception {
        mvc.perform(post("/playlists/select").param("playlistId", "p1")).andExpect(status().isUnauthorized());

        verifyNoInteractions(syncService, recommendationService);
    }

    @Test
    void selectWithoutPlaylistIdIsABadRequest() throws Exception {
        mvc.perform(post("/playlists/select").cookie(SESSION)).andExpect(status().isBadRequest());
    }

    @Test
    void selectSyncsThenSavesThenKicksOffRecommendationsInThatOrder() throws Exception {
        mvc.perform(post("/playlists/select").cookie(SESSION).param("playlistId", "p1")).andExpect(status().isOk());

        assertThat(user.getPlaylistId()).isEqualTo("p1");
        InOrder order = inOrder(syncService, userRepository, recommendationService);
        order.verify(syncService).syncUser(user);
        order.verify(userRepository).save(user);
        order.verify(recommendationService).generateForUserAsync(user);
    }

    @Test
    void selectingAPlaylistSpotifyForbidsRestoresThePreviousOneAndReturns403() throws Exception {
        user.setPlaylistId("previous");
        doThrow(spotifyError(HttpStatus.FORBIDDEN)).when(syncService).syncUser(user);

        mvc.perform(post("/playlists/select").cookie(SESSION).param("playlistId", "p1")).andExpect(status().isForbidden());

        assertThat(user.getPlaylistId()).isEqualTo("previous");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recommendationService);
    }

    @Test
    void otherSyncFailuresRestoreThePreviousPlaylistAndReturn502() throws Exception {
        user.setPlaylistId("previous");
        doThrow(spotifyError(HttpStatus.TOO_MANY_REQUESTS)).when(syncService).syncUser(user);

        mvc.perform(post("/playlists/select").cookie(SESSION).param("playlistId", "p1")).andExpect(status().isBadGateway());

        assertThat(user.getPlaylistId()).isEqualTo("previous");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recommendationService);
    }
}
