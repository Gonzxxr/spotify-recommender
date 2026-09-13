package com.Gonzxxr.spotify_recommender.sync;


import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class PlaylistSyncService {
    private static final Logger log = LoggerFactory.getLogger(PlaylistSyncService.class);
    private static final int MAX_RETRIES = 5;
    private static final long MAX_INLINE_WAIT_SECONDS = 5;

    private final SpotifyAuthService spotifyAuthService;
    private final UserRepository userRepository;
    private final TrackSeenRepository trackSeenRepository;
    private final RestClient restClient;
    private volatile Instant blockedUntil = Instant.EPOCH;

    public PlaylistSyncService(SpotifyAuthService spotifyAuthService, UserRepository userRepository, TrackSeenRepository trackSeenRepository, RestClient.Builder restClientBuilder) {
        this.spotifyAuthService = spotifyAuthService;
        this.userRepository = userRepository;
        this.trackSeenRepository = trackSeenRepository;
        this.restClient = restClientBuilder.build();
    }


    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    public void syncPlaylists() {
        if (Instant.now().isBefore(blockedUntil)) {
            log.warn("Skipping playlist sync tick, still rate-limited until {}", blockedUntil);
            return;
        }
        List<User> users = userRepository.findAll();
        for (User user : users){
            if (user.getPlaylistId() == null) continue;
            try {
                syncUser(user);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Rate limited syncing playlist for user {}, skipping this cycle", user.getSpotifyUserId());
            } catch (Exception e) {
                log.error("Failed to sync playlist for user {}", user.getSpotifyUserId(), e);
            }
        }
    }

    public void syncUser(User user){
        String snapshotId = fetchSnapshotId(user);
        if (snapshotId != null && snapshotId.equals(user.getLastSyncedSnapshotId())) {
            return;
        }

        String url = UriComponentsBuilder.fromUriString("https://api.spotify.com/v1/playlists/{playlistId}/items").buildAndExpand(user.getPlaylistId()).toUriString();
        while(url != null){
            String currentUrl = url;
            SpotifyPlaylistTracksResponse response = executeWithRetry(() ->
                    restClient.get().uri(currentUrl).headers(headers -> headers.setBearerAuth(spotifyAuthService.getValidAccessToken(user))).retrieve().body(SpotifyPlaylistTracksResponse.class));
            List<SpotifyPlaylistItem> items = response.items();
            for (SpotifyPlaylistItem item : items){
                if(item.track() == null || item.track().id() == null) continue;
                if(!trackSeenRepository.existsByUserAndPlaylistIdAndSpotifyTrackId(user, user.getPlaylistId(), item.track().id())){
                    TrackSeen trackSeen = new TrackSeen();
                    trackSeen.setUser(user);
                    trackSeen.setPlaylistId(user.getPlaylistId());
                    trackSeen.setSpotifyTrackId(item.track().id());
                    trackSeen.setTrackName(item.track().name());
                    if (item.track().artists() != null && !item.track().artists().isEmpty()) {
                        trackSeen.setArtistName(item.track().artists().get(0).name());
                    }
                    trackSeen.setAddedToPlaylistAt(item.addedAt());
                    trackSeenRepository.save(trackSeen);
                }
            }
            url = response.next();
        }

        if (snapshotId != null) {
            user.setLastSyncedSnapshotId(snapshotId);
            userRepository.save(user);
        }
    }

    private String fetchSnapshotId(User user) {
        String url = UriComponentsBuilder.fromUriString("https://api.spotify.com/v1/playlists/{playlistId}")
                .queryParam("fields", "snapshot_id")
                .buildAndExpand(user.getPlaylistId())
                .toUriString();

        SpotifyPlaylistSnapshotResponse response = executeWithRetry(() ->
                restClient.get().uri(url).headers(headers -> headers.setBearerAuth(spotifyAuthService.getValidAccessToken(user))).retrieve().body(SpotifyPlaylistSnapshotResponse.class));

        return response != null ? response.snapshotId() : null;
    }

    /**
     * Only retries inline for short waits (a few seconds) — the scheduler that calls this
     * runs on a single shared thread, so sleeping for a long Retry-After here would freeze
     * every other @Scheduled job in the app. A long wait instead sets blockedUntil and fails
     * fast, letting the next scheduled tick (or /playlists/select) pick it back up later.
     */
    private <T> T executeWithRetry(Supplier<T> spotifyCall) {
        int attempts = 0;
        while (true) {
            try {
                return spotifyCall.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                long waitSeconds = retryAfterSeconds(e.getResponseHeaders());
                if (waitSeconds > MAX_INLINE_WAIT_SECONDS) {
                    blockedUntil = Instant.now().plusSeconds(waitSeconds);
                    log.warn("Spotify rate limit hit, pausing playlist sync for {}s (until {})", waitSeconds, blockedUntil);
                    throw e;
                }
                attempts++;
                if (attempts > MAX_RETRIES) {
                    throw e;
                }
                log.warn("Spotify rate limit hit, waiting {}s before retry {}/{}", waitSeconds, attempts, MAX_RETRIES);
                try {
                    TimeUnit.SECONDS.sleep(waitSeconds);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private long retryAfterSeconds(HttpHeaders headers) {
        if (headers == null) return 1;
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null) return 1;
        try {
            return Math.max(1, Long.parseLong(retryAfter));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
