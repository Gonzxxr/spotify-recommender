package com.Gonzxxr.spotify_recommender.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test clock that only moves when told to, so TTL/expiry logic can be exercised without sleeping.
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public static MutableClock atNow() {
        return new MutableClock(Instant.now());
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
