package com.Gonzxxr.spotify_recommender.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.User;

import java.time.Instant;
import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long>{
    boolean existsByUserAndPlaylistIdAndRecommendedTrackId(User user, String playlistId, String recommendedTrackId);
    boolean existsByUserAndPlaylistIdAndRecommendedTrackNameIgnoreCaseAndArtistNameIgnoreCase(
            User user, String playlistId, String recommendedTrackName, String artistName);

    @Query("""
            SELECT r FROM Recommendation r
            WHERE r.user = :user AND r.playlistId = :playlistId
              AND (r.shownAt IS NULL OR r.shownAt < :shownCutoff)
            ORDER BY r.shownAt ASC NULLS FIRST, r.createdAt DESC
            """)
    List<Recommendation> findNextToShow(@Param("user") User user, @Param("playlistId") String playlistId,
                                         @Param("shownCutoff") Instant shownCutoff, Pageable pageable);
}
