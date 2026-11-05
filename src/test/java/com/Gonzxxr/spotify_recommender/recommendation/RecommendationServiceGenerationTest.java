package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.auth.SpotifyAuthService;
import com.Gonzxxr.spotify_recommender.lastfm.LastFmClient;
import com.Gonzxxr.spotify_recommender.lastfm.SimilarTrack;
import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import com.Gonzxxr.spotify_recommender.sync.SpotifySearchService;
import com.Gonzxxr.spotify_recommender.sync.TrackMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Candidate filtering and persistence inside {@link RecommendationService}; the attempt-counter
 * bookkeeping lives in {@link RecommendationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServiceGenerationTest {

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

    private RecommendationService service;
    private User user;
    private TrackSeen source;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(trackSeenRepository, recommendationRepository, userRepository,
                lastFmClient, spotifySearchService, spotifyAuthService,
                new RecommendationProperties(0.5, Duration.ofDays(7)));
        user = TestData.user("u1");
        user.setUserId(1L);
        user.setPlaylistId("pl");
        source = TestData.trackSeen(user, "pl", "src-1", "Source Song", "Source Artist");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(spotifyAuthService.getValidAccessToken(user)).thenReturn("tok");
        when(trackSeenRepository.findPendingForRecommendation(eq(user), eq("pl"), any(), any())).thenReturn(List.of(source));
        when(lastFmClient.getTopTagForArtist(anyString())).thenReturn(Optional.empty());
    }

    private void similar(SimilarTrack... tracks) {
        when(lastFmClient.getSimilarTracks("Source Artist", "Source Song", 5)).thenReturn(List.of(tracks));
    }

    private void spotifyFinds(String name, String artist, String trackId) {
        when(spotifySearchService.findTrack("tok", name, artist)).thenReturn(Optional.of(new TrackMatch(trackId, "http://cover/" + trackId)));
    }

    @Test
    void savesARecommendationWithAllItsFields() {
        similar(new SimilarTrack("Cand", "Cand Artist", 0.8));
        spotifyFinds("Cand", "Cand Artist", "spot-1");
        when(lastFmClient.getTopTagForArtist("Source Artist")).thenReturn(Optional.of("shoegaze"));

        service.generateForUserAsync(user);

        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository).save(saved.capture());
        Recommendation r = saved.getValue();
        assertThat(r.getUser()).isSameAs(user);
        assertThat(r.getSourceTrack()).isSameAs(source);
        assertThat(r.getPlaylistId()).isEqualTo("pl");
        assertThat(r.getRecommendedTrackId()).isEqualTo("spot-1");
        assertThat(r.getRecommendedTrackName()).isEqualTo("Cand");
        assertThat(r.getArtistName()).isEqualTo("Cand Artist");
        assertThat(r.getAlbumImageUrl()).isEqualTo("http://cover/spot-1");
        assertThat(r.getMatchScore()).isEqualTo(0.8);
        assertThat(r.getGenre()).isEqualTo("shoegaze");
        assertThat(r.getCreatedAt()).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(1));
        assertThat(r.getShownAt()).isNull();
    }

    @Test
    void genreIsNullWhenLastFmHasNoTags() {
        similar(new SimilarTrack("Cand", "Cand Artist", 0.8));
        spotifyFinds("Cand", "Cand Artist", "spot-1");

        service.generateForUserAsync(user);

        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository).save(saved.capture());
        assertThat(saved.getValue().getGenre()).isNull();
    }

    @Test
    void savesEveryQualifyingCandidate() {
        similar(new SimilarTrack("A", "AA", 0.9), new SimilarTrack("B", "BB", 0.6));
        spotifyFinds("A", "AA", "id-a");
        spotifyFinds("B", "BB", "id-b");

        service.generateForUserAsync(user);

        verify(recommendationRepository, times(2)).save(any(Recommendation.class));
    }

    @Test
    void candidatesBelowTheMinimumScoreAreSkippedBeforeSearching() {
        similar(new SimilarTrack("Weak", "W", 0.49), new SimilarTrack("Edge", "E", 0.5));
        spotifyFinds("Edge", "E", "id-e");

        service.generateForUserAsync(user);

        verify(spotifySearchService, never()).findTrack(anyString(), eq("Weak"), anyString());
        ArgumentCaptor<Recommendation> saved = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository).save(saved.capture());
        assertThat(saved.getValue().getRecommendedTrackId()).isEqualTo("id-e");
    }

    @Test
    void candidatesWithoutAnArtistAreSkipped() {
        similar(new SimilarTrack("Orphan", null, 0.9));

        service.generateForUserAsync(user);

        verifyNoInteractions(spotifySearchService);
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void candidateAlreadyInThePlaylistByNameIsSkippedWithoutSearching() {
        similar(new SimilarTrack("Dup", "D", 0.9));
        when(trackSeenRepository.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(user, "pl", "Dup", "D"))
                .thenReturn(true);

        service.generateForUserAsync(user);

        verifyNoInteractions(spotifySearchService);
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void candidateAlreadyRecommendedByNameIsSkippedWithoutSearching() {
        similar(new SimilarTrack("Dup", "D", 0.9));
        when(recommendationRepository.existsByUserAndPlaylistIdAndRecommendedTrackNameIgnoreCaseAndArtistNameIgnoreCase(user, "pl", "Dup", "D"))
                .thenReturn(true);

        service.generateForUserAsync(user);

        verifyNoInteractions(spotifySearchService);
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void candidateSpotifyCannotFindIsSkipped() {
        similar(new SimilarTrack("Ghost", "G", 0.9));
        when(spotifySearchService.findTrack("tok", "Ghost", "G")).thenReturn(Optional.empty());

        service.generateForUserAsync(user);

        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void spotifyTrackAlreadyInThePlaylistUnderAnotherNameIsSkipped() {
        similar(new SimilarTrack("Remaster", "R", 0.9));
        spotifyFinds("Remaster", "R", "same-id");
        when(trackSeenRepository.existsByUserAndPlaylistIdAndSpotifyTrackId(user, "pl", "same-id")).thenReturn(true);

        service.generateForUserAsync(user);

        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void spotifyTrackAlreadyRecommendedUnderAnotherNameIsSkipped() {
        similar(new SimilarTrack("Remaster", "R", 0.9));
        spotifyFinds("Remaster", "R", "same-id");
        when(recommendationRepository.existsByUserAndPlaylistIdAndRecommendedTrackId(user, "pl", "same-id")).thenReturn(true);

        service.generateForUserAsync(user);

        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void noSimilarTracksMeansNoSpotifyTokenLookupAndNoRecommendation() {
        similar();

        service.generateForUserAsync(user);

        verifyNoInteractions(spotifyAuthService, spotifySearchService);
        verify(recommendationRepository, never()).save(any());
    }

    @Test
    void trackWhoseUserDisappearedFailsLoudly() {
        similar(new SimilarTrack("Cand", "C", 0.9));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateForUserAsync(user)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void onSelectPathProcessesAtMostFiveTracksToProtectTheSearchQuota() {
        List<TrackSeen> pending = IntStream.range(0, 12)
                .mapToObj(i -> TestData.trackSeen(user, "pl", "t" + i, "Song " + i, "Artist " + i))
                .toList();
        when(trackSeenRepository.findPendingForRecommendation(eq(user), eq("pl"), any(), any())).thenReturn(pending);
        when(lastFmClient.getSimilarTracks(anyString(), anyString(), anyInt())).thenReturn(List.of());

        service.generateForUserAsync(user);

        verify(trackSeenRepository, times(5)).save(any(TrackSeen.class));
        verify(lastFmClient, times(5)).getSimilarTracks(anyString(), anyString(), anyInt());
        assertThat(pending.subList(0, 5)).allMatch(t -> t.getRecommendationAttempts() == 1);
        assertThat(pending.subList(5, 12)).allMatch(t -> t.getRecommendationAttempts() == 0);
    }

    @Test
    void scheduledTickRequestsAPageOfTwentyAndProcessesEachTrack() {
        List<TrackSeen> pending = IntStream.range(0, 3)
                .mapToObj(i -> TestData.trackSeen(user, "pl", "t" + i, "Song " + i, "Artist " + i))
                .toList();
        when(trackSeenRepository.findPendingForRecommendation(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(pending);
        when(lastFmClient.getSimilarTracks(anyString(), anyString(), anyInt())).thenReturn(List.of());

        service.generateRecommendations();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(trackSeenRepository).findPendingForRecommendation(any(Instant.class), any(Instant.class), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(20);
        verify(trackSeenRepository, times(3)).save(any(TrackSeen.class));
    }

    @Test
    void cutoffsAreSixHoursForRetriesAndTheConfiguredCooldownForRepeats() {
        when(trackSeenRepository.findPendingForRecommendation(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        Instant before = Instant.now();

        service.generateRecommendations();

        ArgumentCaptor<Instant> retryCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> reRecommendCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(trackSeenRepository).findPendingForRecommendation(retryCutoff.capture(), reRecommendCutoff.capture(), any(Pageable.class));
        assertThat(Duration.between(retryCutoff.getValue(), before)).isBetween(Duration.ofHours(6).minusSeconds(2), Duration.ofHours(6).plusSeconds(2));
        assertThat(Duration.between(reRecommendCutoff.getValue(), before)).isBetween(Duration.ofDays(7).minusSeconds(2), Duration.ofDays(7).plusSeconds(2));
    }

    @Test
    void nothingPendingDoesNothing() {
        when(trackSeenRepository.findPendingForRecommendation(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        service.generateRecommendations();

        verifyNoInteractions(lastFmClient, spotifySearchService, spotifyAuthService, recommendationRepository);
    }
}
