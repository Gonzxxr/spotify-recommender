package com.Gonzxxr.spotify_recommender.persistence;

import com.Gonzxxr.spotify_recommender.support.TestData;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EntityValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> Set<String> invalidFields(T entity) {
        return validator.validate(entity).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Nested
    class UserValidation {

        @Test
        void fullyPopulatedUserIsValid() {
            assertThat(invalidFields(TestData.user("u1"))).isEmpty();
        }

        @Test
        void spotifyUserIdAccessTokenRefreshTokenAndExpiryAreRequired() {
            assertThat(invalidFields(new User()))
                    .containsExactlyInAnyOrder("spotifyUserId", "accessToken", "refreshToken", "tokenExpiresAt");
        }

        @Test
        void blankStringsCountAsMissing() {
            User user = TestData.user("u1");
            user.setSpotifyUserId("   ");
            user.setAccessToken("");

            assertThat(invalidFields(user)).containsExactlyInAnyOrder("spotifyUserId", "accessToken");
        }

        @Test
        void playlistAndDisplayNameAreOptional() {
            User user = TestData.user("u1");
            user.setPlaylistId(null);
            user.setDisplayName(null);
            user.setLastSyncedSnapshotId(null);

            assertThat(invalidFields(user)).isEmpty();
        }
    }

    @Nested
    class TrackSeenValidation {

        @Test
        void fullyPopulatedTrackSeenIsValid() {
            User user = TestData.user("u1");

            assertThat(invalidFields(TestData.trackSeen(user, "pl", "t1", "Song", "Artist"))).isEmpty();
        }

        @Test
        void userTrackIdAndAddedAtAreRequired() {
            assertThat(invalidFields(new TrackSeen()))
                    .containsExactlyInAnyOrder("user", "spotifyTrackId", "addedToPlaylistAt");
        }

        @Test
        void newTrackStartsWithZeroAttemptsAndNeverAttempted() {
            TrackSeen trackSeen = new TrackSeen();

            assertThat(trackSeen.getRecommendationAttempts()).isZero();
            assertThat(trackSeen.getLastAttemptedAt()).isNull();
        }
    }

    @Nested
    class RecommendationValidation {

        @Test
        void fullyPopulatedRecommendationIsValid() {
            User user = TestData.user("u1");
            TrackSeen source = TestData.trackSeen(user, "pl", "t1", "Song", "Artist");

            assertThat(invalidFields(TestData.recommendation(user, source, "rec1", 0.8))).isEmpty();
        }

        @Test
        void trackIdUserSourceTrackAndScoreAreRequired() {
            assertThat(invalidFields(new Recommendation()))
                    .containsExactlyInAnyOrder("recommendedTrackId", "user", "sourceTrack", "matchScore");
        }

        @Test
        void blankRecommendedTrackIdIsRejected() {
            User user = TestData.user("u1");
            TrackSeen source = TestData.trackSeen(user, "pl", "t1", "Song", "Artist");
            Recommendation recommendation = TestData.recommendation(user, source, " ", 0.5);

            assertThat(invalidFields(recommendation)).containsExactly("recommendedTrackId");
        }

        @Test
        void shownAtStartsEmpty() {
            assertThat(new Recommendation().getShownAt()).isNull();
        }
    }

    @Test
    void gettersAndSettersRoundTrip() {
        Instant now = Instant.now();
        User user = new User();
        user.setUserId(7L);
        user.setLastSyncedSnapshotId("snap");
        user.setCreatedAt(now);

        assertThat(user.getUserId()).isEqualTo(7L);
        assertThat(user.getLastSyncedSnapshotId()).isEqualTo("snap");
        assertThat(user.getCreatedAt()).isEqualTo(now);
    }
}
