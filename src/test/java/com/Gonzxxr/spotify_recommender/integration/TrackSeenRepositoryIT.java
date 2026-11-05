package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackSeenRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private TrackSeenRepository tracks;
    @Autowired
    private RecommendationRepository recommendations;
    @Autowired
    private UserRepository users;

    private User alice;
    private User bob;
    private final Instant now = Instant.now();
    private final Instant retryCutoff = now.minus(Duration.ofHours(6));
    private final Instant reRecommendCutoff = now.minus(Duration.ofDays(7));

    @BeforeEach
    void setUp() {
        alice = users.save(TestData.user("alice"));
        bob = users.save(TestData.user("bob"));
    }

    private TrackSeen track(User user, String playlist, String id, String name, String artist) {
        return tracks.save(TestData.trackSeen(user, playlist, id, name, artist));
    }

    private List<Long> pendingIds() {
        flushAndClear();
        return tracks.findPendingForRecommendation(retryCutoff, reRecommendCutoff, PageRequest.of(0, 100))
                .stream().map(TrackSeen::getTrackSeenId).toList();
    }

    @Test
    void sameTrackCannotBeStoredTwiceForTheSameUserAndPlaylist() {
        track(alice, "pl", "t1", "Song", "Artist");

        assertThatThrownBy(() -> {
            track(alice, "pl", "t1", "Song again", "Artist");
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTrackIsAllowedForAnotherUserOrAnotherPlaylist() {
        track(alice, "pl", "t1", "Song", "Artist");
        track(bob, "pl", "t1", "Song", "Artist");
        track(alice, "pl2", "t1", "Song", "Artist");

        em.flush();
        assertThat(tracks.count()).isEqualTo(3);
    }

    @Test
    void newTrackStartsWithZeroAttemptsAndNoAttemptTimestamp() {
        Long id = track(alice, "pl", "t1", "Song", "Artist").getTrackSeenId();
        flushAndClear();

        TrackSeen loaded = tracks.findById(id).orElseThrow();
        assertThat(loaded.getRecommendationAttempts()).isZero();
        assertThat(loaded.getLastAttemptedAt()).isNull();
    }

    @Test
    void existsBySpotifyIdIsScopedToUserAndPlaylist() {
        track(alice, "pl", "t1", "Song", "Artist");
        flushAndClear();

        assertThat(tracks.existsByUserAndPlaylistIdAndSpotifyTrackId(alice, "pl", "t1")).isTrue();
        assertThat(tracks.existsByUserAndPlaylistIdAndSpotifyTrackId(bob, "pl", "t1")).isFalse();
        assertThat(tracks.existsByUserAndPlaylistIdAndSpotifyTrackId(alice, "other", "t1")).isFalse();
        assertThat(tracks.existsByUserAndPlaylistIdAndSpotifyTrackId(alice, "pl", "t2")).isFalse();
    }

    @Test
    void existsByNameIgnoresCaseButNotUserOrPlaylist() {
        track(alice, "pl", "t1", "Hey Jude", "The Beatles");
        flushAndClear();

        assertThat(tracks.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(alice, "pl", "HEY JUDE", "the beatles")).isTrue();
        assertThat(tracks.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(alice, "pl", "Hey Jude", "Wings")).isFalse();
        assertThat(tracks.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(bob, "pl", "Hey Jude", "The Beatles")).isFalse();
        assertThat(tracks.existsByUserAndPlaylistIdAndTrackNameIgnoreCaseAndArtistNameIgnoreCase(alice, "x", "Hey Jude", "The Beatles")).isFalse();
    }

    @Test
    void neverAttemptedTracksArePending() {
        Long id = track(alice, "pl", "t1", "Song", "Artist").getTrackSeenId();

        assertThat(pendingIds()).containsExactly(id);
    }

    @Test
    void tracksMissingTheirNameOrArtistAreNeverPending() {
        track(alice, "pl", "no-name", null, "Artist");
        track(alice, "pl", "no-artist", "Song", null);

        assertThat(pendingIds()).isEmpty();
    }

    @Test
    void recentlyAttemptedTracksWaitOutTheRetryCooldown() {
        TrackSeen recent = track(alice, "pl", "recent", "S1", "A");
        recent.setLastAttemptedAt(now.minus(Duration.ofHours(1)));
        TrackSeen old = track(alice, "pl", "old", "S2", "A");
        old.setLastAttemptedAt(now.minus(Duration.ofHours(7)));

        assertThat(pendingIds()).containsExactly(old.getTrackSeenId());
    }

    @Test
    void trackThatKeepsFailingIsNeverLockedOutForever() {
        TrackSeen stuck = track(alice, "pl", "stuck", "S", "A");
        stuck.setRecommendationAttempts(50);
        stuck.setLastAttemptedAt(now.minus(Duration.ofHours(7)));

        assertThat(pendingIds()).containsExactly(stuck.getTrackSeenId());
    }

    @Test
    void trackWithARecentRecommendationIsNotPendingUntilTheCooldownPasses() {
        TrackSeen fresh = track(alice, "pl", "fresh", "S1", "A");
        TrackSeen stale = track(alice, "pl", "stale", "S2", "A");
        Recommendation recent = TestData.recommendation(alice, fresh, "r1", 0.5);
        recent.setCreatedAt(now.minus(Duration.ofDays(1)));
        Recommendation old = TestData.recommendation(alice, stale, "r2", 0.5);
        old.setCreatedAt(now.minus(Duration.ofDays(8)));
        recommendations.save(recent);
        recommendations.save(old);

        assertThat(pendingIds()).containsExactly(stale.getTrackSeenId());
    }

    @Test
    void pageSizeCapsHowManyTracksComeBack() {
        for (int i = 0; i < 8; i++) {
            track(alice, "pl", "t" + i, "Song " + i, "Artist");
        }
        flushAndClear();

        assertThat(tracks.findPendingForRecommendation(retryCutoff, reRecommendCutoff, PageRequest.of(0, 3))).hasSize(3);
    }

    @Test
    void perUserVariantIsScopedToThatUsersSelectedPlaylist() {
        TrackSeen mine = track(alice, "pl", "t1", "Song", "Artist");
        track(alice, "other-pl", "t2", "Song 2", "Artist");
        track(bob, "pl", "t3", "Song 3", "Artist");
        flushAndClear();

        List<TrackSeen> pending = tracks.findPendingForRecommendation(alice, "pl", retryCutoff, reRecommendCutoff);

        assertThat(pending).extracting(TrackSeen::getTrackSeenId).containsExactly(mine.getTrackSeenId());
    }

    @Test
    void perUserVariantAppliesTheSameCooldownRules() {
        TrackSeen cooling = track(alice, "pl", "cooling", "S1", "A");
        cooling.setLastAttemptedAt(now.minus(Duration.ofHours(1)));
        TrackSeen recommended = track(alice, "pl", "rec", "S2", "A");
        Recommendation r = TestData.recommendation(alice, recommended, "r1", 0.5);
        r.setCreatedAt(now.minus(Duration.ofHours(2)));
        recommendations.save(r);
        TrackSeen ready = track(alice, "pl", "ready", "S3", "A");
        track(alice, "pl", "nameless", null, null);
        flushAndClear();

        assertThat(tracks.findPendingForRecommendation(alice, "pl", retryCutoff, reRecommendCutoff))
                .extracting(TrackSeen::getTrackSeenId).containsExactly(ready.getTrackSeenId());
    }
}
