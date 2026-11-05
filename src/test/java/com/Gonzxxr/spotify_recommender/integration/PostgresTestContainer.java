package com.Gonzxxr.spotify_recommender.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real Postgres shared by every integration test in the JVM (started on first use, reaped by
 * Testcontainers' Ryuk on exit). Tests hit the same database engine production uses, so JPQL
 * such as NOT EXISTS / NULLS FIRST and the unique constraints are exercised for real.
 */
public final class PostgresTestContainer {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    private PostgresTestContainer() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
