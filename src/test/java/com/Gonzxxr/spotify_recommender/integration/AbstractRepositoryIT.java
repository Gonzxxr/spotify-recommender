package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.security.EncryptedStringConverter;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * JPA slice against real Postgres. Every test runs in a transaction that is rolled back, so tests
 * never see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(EncryptedStringConverter.class)
abstract class AbstractRepositoryIT {

    @Autowired
    protected EntityManager em;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
    }

    /** Pushes pending changes to Postgres and drops the persistence context so the next read hits the DB. */
    protected void flushAndClear() {
        em.flush();
        em.clear();
    }
}
