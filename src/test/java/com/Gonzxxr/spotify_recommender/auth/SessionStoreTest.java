package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    private MutableClock clock;
    private SessionStore store;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atNow();
        store = new SessionStore(clock);
    }

    @Test
    void cookieNameIsStable() {
        assertThat(SessionStore.COOKIE_NAME).isEqualTo("SESSION_ID");
    }

    @Test
    void createdSessionResolvesToItsUser() {
        String sessionId = store.create("spotify-user-1");

        assertThat(store.resolve(sessionId)).contains("spotify-user-1");
    }

    @Test
    void sessionsOfDifferentUsersDoNotMix() {
        String a = store.create("user-a");
        String b = store.create("user-b");

        assertThat(a).isNotEqualTo(b);
        assertThat(store.resolve(a)).contains("user-a");
        assertThat(store.resolve(b)).contains("user-b");
    }

    @Test
    void nullOrUnknownSessionResolvesToEmpty() {
        assertThat(store.resolve(null)).isEmpty();
        assertThat(store.resolve("nope")).isEmpty();
    }

    @Test
    void sessionStaysValidWithinThirtyDays() {
        String sessionId = store.create("user");
        clock.advance(Duration.ofDays(29));

        assertThat(store.resolve(sessionId)).contains("user");
    }

    @Test
    void sessionExpiresAfterThirtyDays() {
        String sessionId = store.create("user");
        clock.advance(Duration.ofDays(30).plusSeconds(1));

        assertThat(store.resolve(sessionId)).isEmpty();
    }

    @Test
    void expiredSessionIsEvictedAndStaysGone() {
        String sessionId = store.create("user");
        clock.advance(Duration.ofDays(31));
        store.resolve(sessionId);
        clock.advance(Duration.ofDays(-31));

        assertThat(store.resolve(sessionId)).isEmpty();
    }

    @Test
    void invalidateRemovesTheSession() {
        String sessionId = store.create("user");

        store.invalidate(sessionId);

        assertThat(store.resolve(sessionId)).isEmpty();
    }

    @Test
    void invalidateIgnoresNullAndUnknownIds() {
        String sessionId = store.create("user");

        store.invalidate(null);
        store.invalidate("unknown");

        assertThat(store.resolve(sessionId)).contains("user");
    }
}
