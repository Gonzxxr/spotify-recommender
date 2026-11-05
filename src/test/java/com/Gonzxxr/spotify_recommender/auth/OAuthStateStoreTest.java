package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthStateStoreTest {

    private MutableClock clock;
    private OAuthStateStore store;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atNow();
        store = new OAuthStateStore(clock);
    }

    @Test
    void createdStateCanBeConsumed() {
        String state = store.create();

        assertThat(store.consume(state)).isTrue();
    }

    @Test
    void stateIsSingleUse() {
        String state = store.create();

        assertThat(store.consume(state)).isTrue();
        assertThat(store.consume(state)).isFalse();
    }

    @Test
    void unknownStateIsRejected() {
        assertThat(store.consume("never-issued")).isFalse();
    }

    @Test
    void eachStateIsUnique() {
        assertThat(store.create()).isNotEqualTo(store.create());
    }

    @Test
    void stateIsAcceptedJustBeforeTenMinutes() {
        String state = store.create();
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));

        assertThat(store.consume(state)).isTrue();
    }

    @Test
    void stateExpiresAfterTenMinutes() {
        String state = store.create();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(store.consume(state)).isFalse();
    }

    @Test
    void expiredStateIsStillRemovedSoItCannotBeRetried() {
        String state = store.create();
        clock.advance(Duration.ofMinutes(11));

        store.consume(state);
        clock.advance(Duration.ofMinutes(-11));

        assertThat(store.consume(state)).isFalse();
    }
}
