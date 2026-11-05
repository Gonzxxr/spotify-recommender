package com.Gonzxxr.spotify_recommender.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    @Test
    void builderProducesAWorkingClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RestClient client = new RestClientConfig().restClientBuilder().build();

            String body = client.get().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/ping")
                    .retrieve().body(String.class);

            assertThat(body).isEqualTo("pong");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildReturnsANewClientEachTime() {
        RestClient.Builder builder = new RestClientConfig().restClientBuilder();

        assertThat(builder.build()).isNotSameAs(builder.build());
    }

    @Test
    void clockBeanIsUtcSystemClock() {
        Clock clock = new ClockConfig().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.instant()).isBetween(java.time.Instant.now().minusSeconds(2), java.time.Instant.now().plusSeconds(2));
    }
}
