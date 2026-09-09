package com.Gonzxxr.spotify_recommender;

import com.Gonzxxr.spotify_recommender.auth.SpotifyProperties;
import com.Gonzxxr.spotify_recommender.lastfm.LastFmProperties;
import com.Gonzxxr.spotify_recommender.recommendation.RecommendationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({SpotifyProperties.class, LastFmProperties.class, RecommendationProperties.class})
@EnableScheduling
@EnableAsync
public class SpotifyRecommenderApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpotifyRecommenderApplication.class, args);
	}

}