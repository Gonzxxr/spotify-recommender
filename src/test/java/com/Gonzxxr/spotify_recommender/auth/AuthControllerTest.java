package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private SpotifyAuthService authService;
    @Mock
    private CurrentUserResolver resolver;

    private OAuthStateStore stateStore;
    private SessionStore sessionStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stateStore = new OAuthStateStore(Clock.systemUTC());
        sessionStore = new SessionStore(Clock.systemUTC());
        mvc = mvcWithCookieSecure(false);
    }

    private MockMvc mvcWithCookieSecure(boolean secure) {
        return MockMvcBuilders.standaloneSetup(new AuthController(authService, stateStore, sessionStore, resolver, secure)).build();
    }

    @Test
    void loginRedirectsToSpotifyWithAFreshStateThatCanBeConsumedLater() throws Exception {
        when(authService.buildAuthorizeUrl(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> "https://accounts.spotify.com/authorize?state=" + inv.getArgument(0));

        String location = mvc.perform(get("/auth/spotify/login"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        assertThat(location).startsWith("https://accounts.spotify.com/authorize?state=");
        String state = location.substring(location.indexOf("state=") + 6);
        assertThat(stateStore.consume(state)).isTrue();
    }

    @Test
    void callbackWithUnknownStateIsRejectedAndNeverTalksToSpotify() throws Exception {
        mvc.perform(get("/auth/spotify/callback").param("code", "c").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verifyNoInteractions(authService);
    }

    @Test
    void callbackWithValidStateCreatesASessionAndSetsAHardenedCookie() throws Exception {
        User user = TestData.user("spotify-1");
        user.setDisplayName("Gonza Pérez");
        when(authService.handleCallback("the-code")).thenReturn(user);
        String state = stateStore.create();

        var response = mvc.perform(get("/auth/spotify/callback").param("code", "the-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?connected=true&displayName=Gonza+P%C3%A9rez"))
                .andReturn().getResponse();

        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).startsWith("SESSION_ID=").contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=2592000");
        assertThat(cookie).doesNotContain("Secure");
        String sessionId = cookie.substring("SESSION_ID=".length(), cookie.indexOf(';'));
        assertThat(sessionStore.resolve(sessionId)).contains("spotify-1");
    }

    @Test
    void stateCannotBeReplayed() throws Exception {
        when(authService.handleCallback("c")).thenReturn(TestData.user("u"));
        String state = stateStore.create();

        mvc.perform(get("/auth/spotify/callback").param("code", "c").param("state", state)).andExpect(status().isFound());
        mvc.perform(get("/auth/spotify/callback").param("code", "c").param("state", state))
                .andExpect(header().string("Location", "/?error=invalid_state"));

        verify(authService, org.mockito.Mockito.times(1)).handleCallback("c");
    }

    @Test
    void cookieIsMarkedSecureWhenConfigured() throws Exception {
        when(authService.handleCallback("c")).thenReturn(TestData.user("u"));
        String state = stateStore.create();

        String cookie = mvcWithCookieSecure(true)
                .perform(get("/auth/spotify/callback").param("code", "c").param("state", state))
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(cookie).contains("Secure");
    }

    @Test
    void callbackWithoutAStateIsTreatedAsInvalidState() throws Exception {
        mvc.perform(get("/auth/spotify/callback").param("code", "c"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=invalid_state"));
        mvc.perform(get("/auth/spotify/callback"))
                .andExpect(header().string("Location", "/?error=invalid_state"));

        verifyNoInteractions(authService);
    }

    @Test
    void personWhoCancelsOnSpotifyIsSentBackWithoutASession() throws Exception {
        String state = stateStore.create();

        mvc.perform(get("/auth/spotify/callback").param("error", "access_denied").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=access_denied"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verifyNoInteractions(authService);
        assertThat(stateStore.consume(state)).isFalse();
    }

    @Test
    void callbackWithValidStateButNoCodeIsTreatedAsDenied() throws Exception {
        String state = stateStore.create();

        mvc.perform(get("/auth/spotify/callback").param("state", state))
                .andExpect(header().string("Location", "/?error=access_denied"));

        verifyNoInteractions(authService);
    }

    @Test
    void spotifyRejectingTheCodeRedirectsWithAnErrorInsteadOfFailing() throws Exception {
        when(authService.handleCallback("bad-code")).thenThrow(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(), new byte[0], null));
        String state = stateStore.create();

        mvc.perform(get("/auth/spotify/callback").param("code", "bad-code").param("state", state))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?error=spotify_unavailable"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void spotifyBeingDownDuringLoginRedirectsWithAnError() throws Exception {
        when(authService.handleCallback("c")).thenThrow(new ResourceAccessException("connect timed out"));
        String state = stateStore.create();

        mvc.perform(get("/auth/spotify/callback").param("code", "c").param("state", state))
                .andExpect(header().string("Location", "/?error=spotify_unavailable"));
    }

    @Test
    void meReturnsTheLoggedInUser() throws Exception {
        User user = TestData.user("spotify-1");
        user.setDisplayName("Gonza");
        when(resolver.resolve("sid")).thenReturn(Optional.of(user));

        mvc.perform(get("/auth/spotify/me").cookie(new Cookie("SESSION_ID", "sid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotifyUserId").value("spotify-1"))
                .andExpect(jsonPath("$.displayName").value("Gonza"));
    }

    @Test
    void meReturnsEmptyDisplayNameWhenTheUserHasNone() throws Exception {
        User user = TestData.user("spotify-1");
        user.setDisplayName(null);
        when(resolver.resolve("sid")).thenReturn(Optional.of(user));

        mvc.perform(get("/auth/spotify/me").cookie(new Cookie("SESSION_ID", "sid")))
                .andExpect(jsonPath("$.displayName").value(""));
    }

    @Test
    void meIsUnauthorizedWithoutAValidSession() throws Exception {
        when(resolver.resolve("stale")).thenReturn(Optional.empty());
        when(resolver.resolve(null)).thenReturn(Optional.empty());

        mvc.perform(get("/auth/spotify/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/spotify/me").cookie(new Cookie("SESSION_ID", "stale"))).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesTheSessionAndExpiresTheCookie() throws Exception {
        String sessionId = sessionStore.create("spotify-1");

        String cookie = mvc.perform(post("/auth/spotify/logout").cookie(new Cookie("SESSION_ID", sessionId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("Set-Cookie");

        assertThat(sessionStore.resolve(sessionId)).isEmpty();
        assertThat(cookie).startsWith("SESSION_ID=;").contains("Max-Age=0", "HttpOnly");
    }

    @Test
    void logoutWithoutACookieStillSucceeds() throws Exception {
        mvc.perform(post("/auth/spotify/logout")).andExpect(status().isOk());
        verify(authService, never()).handleCallback(org.mockito.ArgumentMatchers.anyString());
    }
}
