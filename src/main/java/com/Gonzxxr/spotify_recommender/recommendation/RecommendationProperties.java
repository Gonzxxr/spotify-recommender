package com.Gonzxxr.spotify_recommender.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(double minMatchScore, @DefaultValue("7d") Duration reRecommendCooldown) {
}
