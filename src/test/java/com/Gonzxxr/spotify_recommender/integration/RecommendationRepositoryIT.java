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

class RecommendationRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private RecommendationRepository recommendations;
    @Autowired
    private TrackSeenRepository tracks;
    @Autowired
    private UserRepository users;

    private User alice;
    private User bob;
    private TrackSeen aliceSource;
    private final Instant now = Instant.now();
    private final Instant shownCutoff = now.minus(Duration.ofHours(1));

    @BeforeEach
    void setUp() {
        alice = users.save(TestData.user("alice"));
        bob = users.save(TestData.user("bob"));
        aliceSource = tracks.save(TestData.trackSeen(alice, "pl", "src", "Source", "Artist"));
    }

    private Recommendation rec(String trackId, double score, Instant createdAt, Instant shownAt) {
        Recommendation r = TestData.recommendation(alice, aliceSource, trackId, score);
        r.setCreatedAt(createdAt);
        r.setShownAt(shownAt);
        return recommendations.save(r);
    }

    private List<String> next(int size) {
        flushAndClear();
        return recommendations.findNextToShow(alice, "pl", shownCutoff, PageRequest.of(0, size))
                .stream().map(Recommendation::getRecommendedTrackId).toList();
    }

    @Test
    void sameTrackRecommendedFromTheSameSourceTwiceIsRejected() {
        rec("dup", 0.5, now, null);

        assertThatThrownBy(() -> {
            rec("dup", 0.6, now, null);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTrackFromADifferentSourceIsAllowed() {
        TrackSeen otherSource = tracks.save(TestData.trackSeen(alice, "pl", "src2", "Other", "Artist"));
        rec("same", 0.5, now, null);
        recommendations.save(TestData.recommendation(alice, otherSource, "same", 0.5));

        em.flush();
        assertThat(recommendations.count()).isEqualTo(2);
    }

    @Test
    void sourceTrackAndUserAreLoadedBack() {
        Long id = rec("r1", 0.7, now, null).getRecommendationId();
        flushAndClear();

        Recommendation loaded = recommendations.findById(id).orElseThrow();
        assertThat(loaded.getSourceTrack().getTrackName()).isEqualTo("Source");
        assertThat(loaded.getUser().getSpotifyUserId()).isEqualTo("alice");
        assertThat(loaded.getMatchScore()).isEqualTo(0.7);
    }

    @Test
    void existsByTrackIdIsScopedToUserAndPlaylist() {
        rec("r1", 0.5, now, null);
        flushAndClear();

        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackId(alice, "pl", "r1")).isTrue();
        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackId(bob, "pl", "r1")).isFalse();
        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackId(alice, "other", "r1")).isFalse();
        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackId(alice, "pl", "r2")).isFalse();
    }

    @Test
    void existsByNameIgnoresCase() {
        Recommendation r = TestData.recommendation(alice, aliceSource, "r1", 0.5);
        r.setRecommendedTrackName("Karma Police");
        r.setArtistName("Radiohead");
        recommendations.save(r);
        flushAndClear();

        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackNameIgnoreCaseAndArtistNameIgnoreCase(
                alice, "pl", "KARMA POLICE", "radiohead")).isTrue();
        assertThat(recommendations.existsByUserAndPlaylistIdAndRecommendedTrackNameIgnoreCaseAndArtistNameIgnoreCase(
                bob, "pl", "Karma Police", "Radiohead")).isFalse();
    }

    @Test
    void neverShownComesBeforeShownAndNewestFirstAmongNeverShown() {
        rec("shown-long-ago", 0.5, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));
        rec("new-old", 0.5, now.minus(Duration.ofHours(5)), null);
        rec("new-newest", 0.5, now.minus(Duration.ofMinutes(5)), null);

        assertThat(next(10)).containsExactly("new-newest", "new-old", "shown-long-ago");
    }

    @Test
    void shownOnesRotateOldestFirst() {
        rec("shown-recent", 0.5, now.minus(Duration.ofDays(3)), now.minus(Duration.ofHours(3)));
        rec("shown-oldest", 0.5, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));

        assertThat(next(10)).containsExactly("shown-oldest", "shown-recent");
    }

    @Test
    void recommendationsShownWithinTheCooldownAreHidden() {
        rec("just-shown", 0.5, now, now.minus(Duration.ofMinutes(10)));
        rec("eligible", 0.5, now, null);

        assertThat(next(10)).containsExactly("eligible");
    }

    @Test
    void pageSizeIsRespected() {
        for (int i = 0; i < 6; i++) {
            rec("r" + i, 0.5, now.minusSeconds(i), null);
        }

        assertThat(next(4)).hasSize(4);
    }

    @Test
    void otherUsersAndPlaylistsNeverLeak() {
        TrackSeen bobSource = tracks.save(TestData.trackSeen(bob, "pl", "src", "Source", "Artist"));
        recommendations.save(TestData.recommendation(bob, bobSource, "bobs", 0.9));
        TrackSeen otherPlaylist = tracks.save(TestData.trackSeen(alice, "other", "src", "Source", "Artist"));
        recommendations.save(TestData.recommendation(alice, otherPlaylist, "other-playlist", 0.9));
        rec("mine", 0.5, now, null);

        assertThat(next(10)).containsExactly("mine");
    }

    @Test
    void nothingToShowIsAnEmptyList() {
        assertThat(next(10)).isEmpty();
    }
}
