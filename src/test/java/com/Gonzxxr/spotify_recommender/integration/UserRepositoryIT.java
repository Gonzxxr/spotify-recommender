package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findsAUserBySpotifyId() {
        users.save(TestData.user("spotify-1"));
        flushAndClear();

        assertThat(users.findBySpotifyUserId("spotify-1")).get()
                .extracting(User::getDisplayName).isEqualTo("Name of spotify-1");
    }

    @Test
    void unknownSpotifyIdIsEmpty() {
        assertThat(users.findBySpotifyUserId("nobody")).isEmpty();
    }

    @Test
    void generatesIdsAndKeepsEveryField() {
        User saved = users.save(TestData.user("spotify-1"));
        saved.setPlaylistId("pl");
        saved.setLastSyncedSnapshotId("snap");
        flushAndClear();

        User loaded = users.findById(saved.getUserId()).orElseThrow();

        assertThat(loaded.getUserId()).isNotNull();
        assertThat(loaded.getPlaylistId()).isEqualTo("pl");
        assertThat(loaded.getLastSyncedSnapshotId()).isEqualTo("snap");
        assertThat(loaded.getTokenExpiresAt()).isNotNull();
    }

    @Test
    void spotifyUserIdIsUnique() {
        users.save(TestData.user("dup"));

        assertThatThrownBy(() -> {
            users.save(TestData.user("dup"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("spotify_user_id");
    }

    @Test
    void tokensAreEncryptedInTheDatabaseButReadableThroughTheEntity() {
        User user = TestData.user("spotify-1");
        user.setAccessToken("plain-access-token");
        user.setRefreshToken("plain-refresh-token");
        Long id = users.save(user).getUserId();
        flushAndClear();

        String rawAccess = jdbc.queryForObject("select access_token from users where user_id = ?", String.class, id);
        String rawRefresh = jdbc.queryForObject("select refresh_token from users where user_id = ?", String.class, id);

        assertThat(rawAccess).isNotBlank().doesNotContain("plain-access-token");
        assertThat(rawRefresh).isNotBlank().doesNotContain("plain-refresh-token");
        User loaded = users.findById(id).orElseThrow();
        assertThat(loaded.getAccessToken()).isEqualTo("plain-access-token");
        assertThat(loaded.getRefreshToken()).isEqualTo("plain-refresh-token");
    }

    @Test
    void invalidUsersAreRejectedBeforeReachingTheDatabase() {
        User user = TestData.user("spotify-1");
        user.setAccessToken(" ");

        assertThatThrownBy(() -> {
            users.save(user);
            em.flush();
        }).isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void deleteRemovesTheUser() {
        User saved = users.save(TestData.user("spotify-1"));
        flushAndClear();

        users.deleteById(saved.getUserId());
        flushAndClear();

        assertThat(users.findBySpotifyUserId("spotify-1")).isEmpty();
    }
}
