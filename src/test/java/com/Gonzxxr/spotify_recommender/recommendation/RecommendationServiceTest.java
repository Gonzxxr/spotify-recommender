package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.lastfm.LastFmClient;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.sync.SpotifySearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the attempt-counter/cooldown bookkeeping in
 * {@link RecommendationService#generateForUserAsync(User)}: RETRY_COOLDOWN itself is enforced
 * by the DB query in TrackSeenRepository (integration-test territory), but the reset-at-max
 * and lastAttemptedAt stamping that feed that cooldown are pure logic, testable here with mocks.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private TrackSeenRepository trackSeenRepository;
    @Mock
    private RecommendationRepository recommendationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private LastFmClient lastFmClient;
    @Mock
    private SpotifySearchService spotifySearchService;
    @Mock
    private SpotifyAuthService spotifyAuthService;

    private final RecommendationProperties recommendationProperties =
            new RecommendationProperties(0.5, Duration.ofDays(7));

    @Test
    void resetsAttemptCounterAfterReachingMax() {
        User user = new User();
        user.setPlaylistId("playlist-1");

        TrackSeen trackSeen = new TrackSeen();
        trackSeen.setUser(user);
        trackSeen.setPlaylistId("playlist-1");
        trackSeen.setTrackName("Track");
        trackSeen.setArtistName("Artist");
        trackSeen.setRecommendationAttempts(3); // already at MAX_RECOMMENDATION_ATTEMPTS
        trackSeen.setLastAttemptedAt(Instant.now().minus(Duration.ofHours(7)));

        when(trackSeenRepository.findPendingForRecommendation(
                eq(user), eq("playlist-1"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(trackSeen));
        when(lastFmClient.getSimilarTracks(eq("Artist"), eq("Track"), eq(5)))
                .thenReturn(List.of()); // cuts the flow short right after the counter update

        RecommendationService service = new RecommendationService(
                trackSeenRepository, recommendationRepository, userRepository,
                lastFmClient, spotifySearchService, spotifyAuthService, recommendationProperties);

        service.generateForUserAsync(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository).save(saved.capture());

        assertThat(saved.getValue().getRecommendationAttempts()).isEqualTo(1); // reset to 0, then +1
        assertThat(saved.getValue().getLastAttemptedAt()).isAfter(Instant.now().minus(Duration.ofSeconds(5)));
    }

    @Test
    void incrementsAttemptCounterWhenBelowMax() {
        User user = new User();
        user.setPlaylistId("playlist-1");

        TrackSeen trackSeen = new TrackSeen();
        trackSeen.setUser(user);
        trackSeen.setPlaylistId("playlist-1");
        trackSeen.setTrackName("Track");
        trackSeen.setArtistName("Artist");
        trackSeen.setRecommendationAttempts(1);
        trackSeen.setLastAttemptedAt(Instant.now().minus(Duration.ofHours(7)));

        when(trackSeenRepository.findPendingForRecommendation(
                eq(user), eq("playlist-1"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(trackSeen));
        when(lastFmClient.getSimilarTracks(eq("Artist"), eq("Track"), eq(5)))
                .thenReturn(List.of());

        RecommendationService service = new RecommendationService(
                trackSeenRepository, recommendationRepository, userRepository,
                lastFmClient, spotifySearchService, spotifyAuthService, recommendationProperties);

        service.generateForUserAsync(user);

        ArgumentCaptor<TrackSeen> saved = ArgumentCaptor.forClass(TrackSeen.class);
        verify(trackSeenRepository).save(saved.capture());

        assertThat(saved.getValue().getRecommendationAttempts()).isEqualTo(2); // no reset, just +1
    }
}
