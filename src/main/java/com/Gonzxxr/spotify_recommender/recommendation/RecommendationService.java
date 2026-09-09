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
import com.Gonzxxr.spotify_recommender.sync.SpotifySearchService;
import com.Gonzxxr.spotify_recommender.sync.TrackMatch;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class RecommendationService {

    private static final int SIMILAR_TRACKS_LIMIT = 5;
    private static final int MAX_TRACKS_PER_TICK = 20;
    private static final int MAX_RECOMMENDATION_ATTEMPTS = 3;
    private static final Duration RETRY_COOLDOWN = Duration.ofHours(6);

    private final TrackSeenRepository trackSeenRepository;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final LastFmClient lastFmClient;
    private final SpotifySearchService spotifySearchService;
    private final SpotifyAuthService spotifyAuthService;
    private final RecommendationProperties recommendationProperties;

    public RecommendationService(TrackSeenRepository trackSeenRepository,
                                  RecommendationRepository recommendationRepository,
                                  UserRepository userRepository,
                                  LastFmClient lastFmClient,
                                  SpotifySearchService spotifySearchService,
                                  SpotifyAuthService spotifyAuthService,
                                  RecommendationProperties recommendationProperties) {
        this.trackSeenRepository = trackSeenRepository;
        this.recommendationRepository = recommendationRepository;
        this.userRepository = userRepository;
        this.lastFmClient = lastFmClient;
        this.spotifySearchService = spotifySearchService;
        this.spotifyAuthService = spotifyAuthService;
        this.recommendationProperties = recommendationProperties;
    }

    @Scheduled(fixedRate = 60, timeUnit = TimeUnit.SECONDS)
    public void generateRecommendations() {
        Instant retryCutoff = Instant.now().minus(RETRY_COOLDOWN);
        Instant reRecommendCutoff = Instant.now().minus(recommendationProperties.reRecommendCooldown());
        List<TrackSeen> pending = trackSeenRepository.findPendingForRecommendation(
                MAX_RECOMMENDATION_ATTEMPTS, retryCutoff, reRecommendCutoff, PageRequest.of(0, MAX_TRACKS_PER_TICK));
        for (TrackSeen trackSeen : pending) {
            generateForTrack(trackSeen);
        }
    }

    @Async
    public void generateForUserAsync(User user) {
        Instant retryCutoff = Instant.now().minus(RETRY_COOLDOWN);
        Instant reRecommendCutoff = Instant.now().minus(recommendationProperties.reRecommendCooldown());
        List<TrackSeen> pending = trackSeenRepository.findPendingForRecommendation(
                user, user.getPlaylistId(), MAX_RECOMMENDATION_ATTEMPTS, retryCutoff, reRecommendCutoff);
        for (TrackSeen trackSeen : pending) {
            generateForTrack(trackSeen);
        }
    }

    private void generateForTrack(TrackSeen trackSeen) {
        if (trackSeen.getRecommendationAttempts() >= MAX_RECOMMENDATION_ATTEMPTS) {
            trackSeen.setRecommendationAttempts(0);
        }
        trackSeen.setRecommendationAttempts(trackSeen.getRecommendationAttempts() + 1);
        trackSeen.setLastAttemptedAt(Instant.now());
        trackSeenRepository.save(trackSeen);

        List<SimilarTrack> similarTracks = lastFmClient.getSimilarTracks(
                trackSeen.getArtistName(), trackSeen.getTrackName(), SIMILAR_TRACKS_LIMIT);
        if (similarTracks.isEmpty()) return;

        User user = userRepository.findById(trackSeen.getUser().getUserId()).orElseThrow();
        String accessToken = spotifyAuthService.getValidAccessToken(user);
        Optional<String> genre = lastFmClient.getTopTagForArtist(trackSeen.getArtistName());

        for (SimilarTrack similar : similarTracks) {
            if (similar.artistName() == null) continue;
            if (similar.match() < recommendationProperties.minMatchScore()) continue;

            if (trackSeenRepository.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(
                    user, trackSeen.getPlaylistId(), similar.name(), similar.artistName())) continue;
            if (recommendationRepository.existsByUserAndPlaylistIdAndRecommendedTrackNameIgnoreCaseAndArtistNameIgnoreCase(
                    user, trackSeen.getPlaylistId(), similar.name(), similar.artistName())) continue;

            Optional<TrackMatch> match = spotifySearchService.findTrack(accessToken, similar.name(), similar.artistName());
            if (match.isEmpty()) continue;
            String trackId = match.get().trackId();

            if (trackSeenRepository.existsByUserAndPlaylistIdAndSpotifyTrackId(user, trackSeen.getPlaylistId(), trackId)) continue;
            if (recommendationRepository.existsByUserAndPlaylistIdAndRecommendedTrackId(user, trackSeen.getPlaylistId(), trackId)) continue;

            Recommendation recommendation = new Recommendation();
            recommendation.setUser(user);
            recommendation.setPlaylistId(trackSeen.getPlaylistId());
            recommendation.setSourceTrack(trackSeen);
            recommendation.setRecommendedTrackId(trackId);
            recommendation.setRecommendedTrackName(similar.name());
            recommendation.setArtistName(similar.artistName());
            recommendation.setAlbumImageUrl(match.get().albumImageUrl());
            recommendation.setMatchScore(similar.match());
            recommendation.setGenre(genre.orElse(null));
            recommendation.setCreatedAt(Instant.now());
            recommendationRepository.save(recommendation);
        }
    }
}
