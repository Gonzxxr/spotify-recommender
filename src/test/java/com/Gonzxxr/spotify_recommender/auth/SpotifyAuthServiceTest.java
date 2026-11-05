package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.MockHttp;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class SpotifyAuthServiceTest {

    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String PROFILE_URL = "https://api.spotify.com/v1/me";
    private static final String BASIC = "Basic " + Base64.getEncoder()
            .encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));

    @Mock
    private UserRepository userRepository;

    private MockHttp http;
    private SpotifyAuthService service;

    @BeforeEach
    void setUp() {
        http = MockHttp.create();
        service = new SpotifyAuthService(
                new SpotifyProperties("client-id", "client-secret", "http://localhost/cb"), userRepository, http.builder());
    }

    @Test
    void authorizeUrlCarriesAllOAuthParameters() {
        String url = service.buildAuthorizeUrl("state-123");

        var params = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        assertThat(url).startsWith("https://accounts.spotify.com/authorize?");
        assertThat(params.getFirst("client_id")).isEqualTo("client-id");
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(params.getFirst("redirect_uri")).isEqualTo("http://localhost/cb");
        assertThat(params.getFirst("scope")).isEqualTo("playlist-read-private");
        assertThat(params.getFirst("state")).isEqualTo("state-123");
    }

    @Test
    void callbackCreatesNewUserWithTokensAndExpiry() {
        expectCodeExchange("access-1", "refresh-1", 3600);
        expectProfile("access-1", "spotify-1", "Gonza");
        when(userRepository.findBySpotifyUserId("spotify-1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = service.handleCallback("the-code");

        assertThat(user.getSpotifyUserId()).isEqualTo("spotify-1");
        assertThat(user.getDisplayName()).isEqualTo("Gonza");
        assertThat(user.getAccessToken()).isEqualTo("access-1");
        assertThat(user.getRefreshToken()).isEqualTo("refresh-1");
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getTokenExpiresAt()).isBetween(
                Instant.now().plusSeconds(3590), Instant.now().plusSeconds(3610));
        http.server().verify();
    }

    @Test
    void callbackSendsAuthorizationCodeGrantWithBasicAuth() {
        http.server().expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("code=the-code"),
                        org.hamcrest.Matchers.containsString("redirect_uri=http%3A%2F%2Flocalhost%2Fcb"))))
                .andRespond(withSuccess("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":60}", MediaType.APPLICATION_JSON));
        expectProfile("a", "s1", "N");
        when(userRepository.findBySpotifyUserId("s1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleCallback("the-code");

        http.server().verify();
    }

    @Test
    void callbackForReturningUserUpdatesTokensButKeepsIdentityAndCreatedAt() {
        User existing = TestData.user("spotify-1");
        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        existing.setCreatedAt(createdAt);
        existing.setPlaylistId("keep-me");
        expectCodeExchange("new-access", "new-refresh", 1800);
        expectProfile("new-access", "spotify-1", "Renamed");
        when(userRepository.findBySpotifyUserId("spotify-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        User user = service.handleCallback("code");

        assertThat(user).isSameAs(existing);
        assertThat(user.getAccessToken()).isEqualTo("new-access");
        assertThat(user.getRefreshToken()).isEqualTo("new-refresh");
        assertThat(user.getDisplayName()).isEqualTo("Renamed");
        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getPlaylistId()).isEqualTo("keep-me");
    }

    @Test
    void callbackFailsWhenSpotifyRejectsTheCode() {
        http.server().expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.handleCallback("bad-code"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void validTokenIsReturnedWithoutCallingSpotify() {
        User user = TestData.user("u");
        user.setAccessToken("still-good");
        user.setTokenExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));

        assertThat(service.getValidAccessToken(user)).isEqualTo("still-good");
        verify(userRepository, never()).save(any());
        http.server().verify();
    }

    @Test
    void tokenExpiringWithinTheMarginIsRefreshed() {
        User user = TestData.user("u");
        user.setRefreshToken("old-refresh");
        user.setTokenExpiresAt(Instant.now().plusSeconds(30));
        http.server().expect(requestTo(TOKEN_URL))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=refresh_token"),
                        org.hamcrest.Matchers.containsString("refresh_token=old-refresh"))))
                .andRespond(withSuccess("{\"access_token\":\"fresh\",\"refresh_token\":\"rotated\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        String token = service.getValidAccessToken(user);

        assertThat(token).isEqualTo("fresh");
        assertThat(user.getRefreshToken()).isEqualTo("rotated");
        assertThat(user.getTokenExpiresAt()).isAfter(Instant.now().plusSeconds(3500));
        verify(userRepository).save(user);
        http.server().verify();
    }

    @Test
    void refreshKeepsTheOldRefreshTokenWhenSpotifyDoesNotRotateIt() {
        User user = TestData.user("u");
        user.setRefreshToken("keep-this");
        user.setTokenExpiresAt(Instant.now().minusSeconds(10));
        http.server().expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"fresh\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        service.getValidAccessToken(user);

        assertThat(user.getAccessToken()).isEqualTo("fresh");
        assertThat(user.getRefreshToken()).isEqualTo("keep-this");
    }

    @Test
    void failedRefreshDoesNotTouchTheStoredUser() {
        User user = TestData.user("u");
        user.setAccessToken("old");
        user.setTokenExpiresAt(Instant.now().minusSeconds(10));
        http.server().expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.getValidAccessToken(user)).isInstanceOf(HttpClientErrorException.class);
        assertThat(user.getAccessToken()).isEqualTo("old");
        verify(userRepository, never()).save(any());
    }

    private void expectCodeExchange(String access, String refresh, int expiresIn) {
        http.server().expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"%s\",\"refresh_token\":\"%s\",\"expires_in\":%d}"
                        .formatted(access, refresh, expiresIn), MediaType.APPLICATION_JSON));
    }

    private void expectProfile(String bearer, String id, String displayName) {
        http.server().expect(requestTo(PROFILE_URL))
                .andExpect(header("Authorization", "Bearer " + bearer))
                .andRespond(withSuccess("{\"id\":\"%s\",\"display_name\":\"%s\"}".formatted(id, displayName), MediaType.APPLICATION_JSON));
    }
}
