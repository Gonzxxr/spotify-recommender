package com.Gonzxxr.spotify_recommender.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;

import java.time.Instant;
import java.util.List;

public interface TrackSeenRepository extends JpaRepository<TrackSeen, Long> {
    boolean existsByUserAndPlaylistIdAndSpotifyTrackId(User user, String playlistId, String spotifyTrackId);
    boolean existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(
            User user, String playlistId, String trackName, String artistName);

    @Query("""
            SELECT ts FROM TrackSeen ts
            WHERE ts.trackName IS NOT NULL AND ts.artistName IS NOT NULL
              AND (ts.lastAttemptedAt IS NULL OR ts.lastAttemptedAt < :retryCutoff)
              AND NOT EXISTS (SELECT 1 FROM Recommendation r WHERE r.sourceTrack = ts AND r.createdAt > :reRecommendCutoff)
              AND (
                    ts.recommendationAttempts < :maxAttempts
                    OR EXISTS (SELECT 1 FROM Recommendation r2 WHERE r2.sourceTrack = ts)
                  )
            """)
    List<TrackSeen> findPendingForRecommendation(@Param("maxAttempts") int maxAttempts, @Param("retryCutoff") Instant retryCutoff,
                                                  @Param("reRecommendCutoff") Instant reRecommendCutoff, Pageable pageable);

    @Query("""
            SELECT ts FROM TrackSeen ts
            WHERE ts.user = :user AND ts.playlistId = :playlistId
              AND ts.trackName IS NOT NULL AND ts.artistName IS NOT NULL
              AND (ts.lastAttemptedAt IS NULL OR ts.lastAttemptedAt < :retryCutoff)
              AND NOT EXISTS (SELECT 1 FROM Recommendation r WHERE r.sourceTrack = ts AND r.createdAt > :reRecommendCutoff)
              AND (
                    ts.recommendationAttempts < :maxAttempts
                    OR EXISTS (SELECT 1 FROM Recommendation r2 WHERE r2.sourceTrack = ts)
                  )
            """)
    List<TrackSeen> findPendingForRecommendation(@Param("user") User user, @Param("playlistId") String playlistId,
                                                  @Param("maxAttempts") int maxAttempts, @Param("retryCutoff") Instant retryCutoff,
                                                  @Param("reRecommendCutoff") Instant reRecommendCutoff);
}
