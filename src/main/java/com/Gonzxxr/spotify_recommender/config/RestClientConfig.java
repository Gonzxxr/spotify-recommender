package com.Gonzxxr.spotify_recommender.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Every RestClient in this app talked directly to Spotify/Last.fm with no timeout at all,
 * so a stalled connection could block a caller forever. Since the single-threaded default
 * scheduler pool is shared across all @Scheduled jobs, one such hang froze every scheduled
 * job silently (no crash, no log) until the process was restarted.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
