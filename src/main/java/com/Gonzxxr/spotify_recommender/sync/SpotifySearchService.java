package com.Gonzxxr.spotify_recommender.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;

@Service
public class SpotifySearchService {

    private static final Logger log = LoggerFactory.getLogger(SpotifySearchService.class);
    private static final String SEARCH_URL = "https://api.spotify.com/v1/search";
    private static final long DEFAULT_RETRY_SECONDS = 5;
    private static final long MIN_INTERVAL_MS = 400;

    private final RestClient restClient = RestClient.create();
    private volatile Instant blockedUntil = Instant.EPOCH;
    private Instant lastCallAt = Instant.EPOCH;

    /**
     * Synchronized so every call to Spotify Search — regardless of which thread triggers it
     * (scheduler, on-demand playlist switch, etc.) — is globally serialized and paced. Spotify's
     * rate limit is per-app, not per-caller, so concurrent callers must not be able to burst it.
     */
    public synchronized Optional<TrackMatch> findTrack(String accessToken, String trackName, String artistName) {
        if (Instant.now().isBefore(blockedUntil)) {
            return Optional.empty();
        }
        pace();

        String query = "track:%s artist:%s".formatted(trackName, artistName);
        String url = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("q", query)
                .queryParam("type", "track")
                .queryParam("limit", 1)
                .build()
                .toUriString();

        try {
            SpotifySearchResponse response = restClient.get()
                    .uri(url)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(SpotifySearchResponse.class);

            lastCallAt = Instant.now();
            if (response == null || response.tracks() == null || response.tracks().items() == null || response.tracks().items().isEmpty()) {
                return Optional.empty();
            }
            SpotifyTrackRef track = response.tracks().items().get(0);
            if (track.id() == null) return Optional.empty();
            return Optional.of(new TrackMatch(track.id(), track.albumImageUrl()));
        } catch (HttpClientErrorException.TooManyRequests e) {
            lastCallAt = Instant.now();
            long retryAfterSeconds = parseRetryAfter(e);
            blockedUntil = Instant.now().plusSeconds(retryAfterSeconds);
            log.warn("Spotify search rate-limited, pausing all search calls for {}s (until {})", retryAfterSeconds, blockedUntil);
            return Optional.empty();
        } catch (RestClientException e) {
            lastCallAt = Instant.now();
            log.warn("Spotify search failed for track='{}' artist='{}': {}", trackName, artistName, e.getMessage());
            return Optional.empty();
        }
    }

    private void pace() {
        long elapsedMs = Instant.now().toEpochMilli() - lastCallAt.toEpochMilli();
        long waitMs = MIN_INTERVAL_MS - elapsedMs;
        if (waitMs <= 0) return;
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long parseRetryAfter(HttpClientErrorException.TooManyRequests e) {
        String header = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (header == null) return DEFAULT_RETRY_SECONDS;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_RETRY_SECONDS;
        }
    }
}
