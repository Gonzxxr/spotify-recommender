package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;

@Service
public class SpotifyAuthService {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String PROFILE_URL = "https://api.spotify.com/v1/me";
    private static final String SCOPE = "playlist-read-private";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    private final SpotifyProperties properties;
    private final UserRepository userRepository;
    private final RestClient restClient = RestClient.create();

    public SpotifyAuthService(SpotifyProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", properties.clientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    public User handleCallback(String code) {
        SpotifyTokenResponse tokenResponse = exchangeCodeForToken(code);
        SpotifyProfileResponse profile = fetchProfile(tokenResponse.accessToken());
        return upsertUser(profile, tokenResponse);
    }

    public String getValidAccessToken(User user) {
        if (Instant.now().plus(REFRESH_MARGIN).isAfter(user.getTokenExpiresAt())) {
            refresh(user);
        }
        return user.getAccessToken();
    }

    private SpotifyTokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        return restClient.post()
                .uri(TOKEN_URL)
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(SpotifyTokenResponse.class);
    }

    private SpotifyProfileResponse fetchProfile(String accessToken) {
        return restClient.get()
                .uri(PROFILE_URL)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(SpotifyProfileResponse.class);
    }

    private void refresh(User user) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", user.getRefreshToken());

        SpotifyTokenResponse tokenResponse = restClient.post()
                .uri(TOKEN_URL)
                .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(SpotifyTokenResponse.class);

        applyToken(user, tokenResponse);
        userRepository.save(user);
    }

    private User upsertUser(SpotifyProfileResponse profile, SpotifyTokenResponse tokenResponse) {
        User user = userRepository.findBySpotifyUserId(profile.spotifyUserId())
                .orElseGet(User::new);

        user.setSpotifyUserId(profile.spotifyUserId());
        user.setDisplayName(profile.displayName());
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(Instant.now());
        }
        applyToken(user, tokenResponse);

        return userRepository.save(user);
    }

    private void applyToken(User user, SpotifyTokenResponse tokenResponse) {
        user.setAccessToken(tokenResponse.accessToken());
        if (tokenResponse.refreshToken() != null) {
            user.setRefreshToken(tokenResponse.refreshToken());
        }
        user.setTokenExpiresAt(Instant.now().plusSeconds(tokenResponse.expiresInSeconds()));
    }
}
