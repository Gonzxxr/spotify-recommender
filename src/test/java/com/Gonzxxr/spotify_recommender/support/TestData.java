package com.Gonzxxr.spotify_recommender.support;

import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;

import java.time.Instant;

/**
 * Builders for valid entities, so each test only spells out the fields it actually cares about.
 */
public final class TestData {

    private TestData() {
    }

    public static User user(String spotifyUserId) {
        User user = new User();
        user.setSpotifyUserId(spotifyUserId);
        user.setDisplayName("Name of " + spotifyUserId);
        user.setAccessToken("access-" + spotifyUserId);
        user.setRefreshToken("refresh-" + spotifyUserId);
        user.setTokenExpiresAt(Instant.now().plusSeconds(3600));
        user.setCreatedAt(Instant.now());
        return user;
    }

    public static TrackSeen trackSeen(User user, String playlistId, String spotifyTrackId, String trackName, String artistName) {
        TrackSeen trackSeen = new TrackSeen();
        trackSeen.setUser(user);
        trackSeen.setPlaylistId(playlistId);
        trackSeen.setSpotifyTrackId(spotifyTrackId);
        trackSeen.setTrackName(trackName);
        trackSeen.setArtistName(artistName);
        trackSeen.setAddedToPlaylistAt(Instant.now());
        return trackSeen;
    }

    public static Recommendation recommendation(User user, TrackSeen source, String recommendedTrackId, double matchScore) {
        Recommendation recommendation = new Recommendation();
        recommendation.setUser(user);
        recommendation.setSourceTrack(source);
        recommendation.setPlaylistId(source.getPlaylistId());
        recommendation.setRecommendedTrackId(recommendedTrackId);
        recommendation.setRecommendedTrackName("Rec " + recommendedTrackId);
        recommendation.setArtistName("Rec Artist");
        recommendation.setMatchScore(matchScore);
        recommendation.setCreatedAt(Instant.now());
        return recommendation;
    }
}
