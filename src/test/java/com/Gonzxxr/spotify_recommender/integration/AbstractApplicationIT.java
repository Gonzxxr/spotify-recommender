package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.recommendation.RecommendationService;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.repository.TrackSeenRepository;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.MockHttp;
import com.Gonzxxr.spotify_recommender.sync.PlaylistSyncService;
import com.Gonzxxr.spotify_recommender.sync.SpotifySearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

/**
 * Full Spring context + real Postgres, with Spotify and Last.fm replaced by an HTTP-level mock.
 * Scheduling is off (see application-test.properties), so tests trigger the jobs themselves.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ExternalApisTestConfig.class)
abstract class AbstractApplicationIT {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MockHttp externalApis;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected TrackSeenRepository trackSeen;
    @Autowired
    protected RecommendationRepository recommendations;
    @Autowired
    protected SpotifySearchService searchService;
    @Autowired
    protected PlaylistSyncService syncService;
    @Autowired
    protected RecommendationService recommendationService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
    }

    @AfterEach
    void leaveTheDatabaseEmpty() {
        jdbc.execute("TRUNCATE TABLE recommendation, track_seen, users RESTART IDENTITY CASCADE");
    }

    @BeforeEach
    void resetSharedState() {
        externalApis.server().reset();
        // TRUNCATE runs immediately; deleteAll() would reorder after inserts of the next seed within one flush
        jdbc.execute("TRUNCATE TABLE recommendation, track_seen, users RESTART IDENTITY CASCADE");
        // in-memory state that lives in singleton beans and would otherwise leak between tests
        Object search = AopProxyUtils.getSingletonTarget(searchService) != null ? AopProxyUtils.getSingletonTarget(searchService) : searchService;
        ((Map<?, ?>) ReflectionTestUtils.getField(search, "cache")).clear();
        ReflectionTestUtils.setField(search, "blockedUntil", Instant.EPOCH);
        Object sync = AopProxyUtils.getSingletonTarget(syncService) != null ? AopProxyUtils.getSingletonTarget(syncService) : syncService;
        ReflectionTestUtils.setField(sync, "blockedUntil", Instant.EPOCH);
    }
}
