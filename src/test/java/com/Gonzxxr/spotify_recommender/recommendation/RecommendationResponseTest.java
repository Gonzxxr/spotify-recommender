package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationResponseTest {

    @Test
    void mapsEveryFieldIncludingTheSourceTrack() {
        User user = TestData.user("u");
        TrackSeen source = TestData.trackSeen(user, "pl", "src", "Source Song", "Source Artist");
        Recommendation rec = TestData.recommendation(user, source, "rec-1", 0.75);
        rec.setRecommendedTrackName("Rec Song");
        rec.setArtistName("Rec Artist");
        rec.setAlbumImageUrl("http://img");
        rec.setGenre("rock");
        Instant createdAt = Instant.parse("2024-06-01T00:00:00Z");
        rec.setCreatedAt(createdAt);

        RecommendationResponse response = RecommendationResponse.from(rec);

        assertThat(response).isEqualTo(new RecommendationResponse(
                "rec-1", "Rec Song", "Rec Artist", "http://img", 0.75, "rock",
                "Source Song", "Source Artist", createdAt));
    }

    @Test
    void optionalFieldsMayBeNull() {
        User user = TestData.user("u");
        TrackSeen source = TestData.trackSeen(user, "pl", "src", "S", "A");
        Recommendation rec = TestData.recommendation(user, source, "rec-1", 0.5);
        rec.setAlbumImageUrl(null);
        rec.setGenre(null);

        RecommendationResponse response = RecommendationResponse.from(rec);

        assertThat(response.albumImageUrl()).isNull();
        assertThat(response.genre()).isNull();
    }
}
