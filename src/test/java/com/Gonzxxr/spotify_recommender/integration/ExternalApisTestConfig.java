package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.support.MockHttp;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Replaces the real network with a MockRestServiceServer. Every service builds its RestClient from
 * the same RestClient.Builder bean, so making this one primary intercepts all calls to Spotify and
 * Last.fm while the services, serialization and error handling stay the production code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExternalApisTestConfig {

    @Bean
    MockHttp externalApis() {
        return MockHttp.create();
    }

    @Bean
    @Primary
    RestClient.Builder externalApisRestClientBuilder(MockHttp externalApis) {
        return externalApis.builder();
    }
}
