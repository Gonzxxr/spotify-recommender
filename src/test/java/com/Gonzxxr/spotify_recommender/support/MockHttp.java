package com.Gonzxxr.spotify_recommender.support;

import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A RestClient.Builder wired to a MockRestServiceServer, standing in for Spotify/Last.fm.
 * Order-insensitive, because the services under test make independent calls.
 */
public record MockHttp(RestClient.Builder builder, MockRestServiceServer server) {

    public static MockHttp create() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new MockHttp(builder, server);
    }
}
