package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.persistence.Recommendation;

import java.time.Instant;

public record RecommendationResponse(
        String recommendedTrackId,
        String recommendedTrackName,
        String artistName,
        String albumImageUrl,
        double matchScore,
        String genre,
        String sourceTrackName,
        String sourceArtistName,
        Instant createdAt
) {
    public static RecommendationResponse from(Recommendation recommendation) {
        return new RecommendationResponse(
                recommendation.getRecommendedTrackId(),
                recommendation.getRecommendedTrackName(),
                recommendation.getArtistName(),
                recommendation.getAlbumImageUrl(),
                recommendation.getMatchScore(),
                recommendation.getGenre(),
                recommendation.getSourceTrack().getTrackName(),
                recommendation.getSourceTrack().getArtistName(),
                recommendation.getCreatedAt()
        );
    }
}
