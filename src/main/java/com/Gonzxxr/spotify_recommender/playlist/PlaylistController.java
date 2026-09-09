package com.Gonzxxr.spotify_recommender.playlist;

import com.Gonzxxr.spotify_recommender.auth.CurrentUserResolver;
import com.Gonzxxr.spotify_recommender.auth.SessionStore;
import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.recommendation.RecommendationService;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.sync.PlaylistSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;

@RestController
@RequestMapping("/playlists")
public class PlaylistController {

    private final UserRepository userRepository;
    private final CurrentUserResolver currentUserResolver;
    private final SpotifyAuthService spotifyAuthService;
    private final PlaylistService playlistService;
    private final PlaylistSyncService playlistSyncService;
    private final RecommendationService recommendationService;

    public PlaylistController(UserRepository userRepository, CurrentUserResolver currentUserResolver, SpotifyAuthService spotifyAuthService,
                               PlaylistService playlistService, PlaylistSyncService playlistSyncService,
                               RecommendationService recommendationService) {
        this.userRepository = userRepository;
        this.currentUserResolver = currentUserResolver;
        this.spotifyAuthService = spotifyAuthService;
        this.playlistService = playlistService;
        this.playlistSyncService = playlistSyncService;
        this.recommendationService = recommendationService;
    }

    @GetMapping
    public ResponseEntity<List<PlaylistSummary>> listPlaylists(@CookieValue(value = SessionStore.COOKIE_NAME, required = false) String sessionId) {
        return currentUserResolver.resolve(sessionId)
                .map(user -> {
                    try {
                        return ResponseEntity.ok(playlistService.fetchUserPlaylists(spotifyAuthService.getValidAccessToken(user), user.getSpotifyUserId()));
                    } catch (HttpClientErrorException.TooManyRequests e) {
                        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).<List<PlaylistSummary>>build();
                    } catch (RestClientException e) {
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).<List<PlaylistSummary>>build();
                    }
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @PostMapping("/select")
    public ResponseEntity<Void> selectPlaylist(@CookieValue(value = SessionStore.COOKIE_NAME, required = false) String sessionId,
                                                @RequestParam String playlistId) {
        return currentUserResolver.resolve(sessionId)
                .map(user -> {
                    String previousPlaylistId = user.getPlaylistId();
                    user.setPlaylistId(playlistId);
                    try {
                        playlistSyncService.syncUser(user);
                    } catch (HttpClientErrorException.Forbidden e) {
                        user.setPlaylistId(previousPlaylistId);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<Void>build();
                    } catch (RestClientException e) {
                        user.setPlaylistId(previousPlaylistId);
                        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build();
                    }
                    User saved = userRepository.save(user);
                    recommendationService.generateForUserAsync(saved);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).<Void>build());
    }
}
