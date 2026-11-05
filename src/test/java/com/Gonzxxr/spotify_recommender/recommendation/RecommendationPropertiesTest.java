package com.Gonzxxr.spotify_recommender.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationPropertiesTest {

    private RecommendationProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props)).bind("recommendation", RecommendationProperties.class).get();
    }

    @Test
    void bindsThresholdAndCooldownFromConfiguration() {
        RecommendationProperties properties = bind(Map.of(
                "recommendation.min-match-score", "0.25",
                "recommendation.re-recommend-cooldown", "24h"));

        assertThat(properties.minMatchScore()).isEqualTo(0.25);
        assertThat(properties.reRecommendCooldown()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void cooldownDefaultsToSevenDays() {
        assertThat(bind(Map.of("recommendation.min-match-score", "0.1")).reRecommendCooldown())
                .isEqualTo(Duration.ofDays(7));
    }
}
