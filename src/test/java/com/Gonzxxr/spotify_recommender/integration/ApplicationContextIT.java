package com.Gonzxxr.spotify_recommender.integration;

import com.Gonzxxr.spotify_recommender.auth.SpotifyProperties;
import com.Gonzxxr.spotify_recommender.lastfm.LastFmProperties;
import com.Gonzxxr.spotify_recommender.recommendation.RecommendationProperties;
import com.Gonzxxr.spotify_recommender.security.EncryptedStringConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationContextIT extends AbstractApplicationIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsAgainstARealDatabase() {
        assertThat(users.count()).isZero();
    }

    @Test
    void configurationPropertiesAreBound() {
        assertThat(context.getBean(SpotifyProperties.class).clientId()).isEqualTo("test-client-id");
        assertThat(context.getBean(SpotifyProperties.class).redirectUri()).isEqualTo("http://127.0.0.1:8080/auth/spotify/callback");
        assertThat(context.getBean(LastFmProperties.class).apiKey()).isEqualTo("test-lastfm-key");
        RecommendationProperties recommendation = context.getBean(RecommendationProperties.class);
        assertThat(recommendation.minMatchScore()).isEqualTo(0.1);
        assertThat(recommendation.reRecommendCooldown()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void tokenEncryptionConverterIsWiredWithTheConfiguredKey() {
        EncryptedStringConverter converter = context.getBean(EncryptedStringConverter.class);

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn("x"))).isEqualTo("x");
    }

    @Test
    void schedulingCanBeSwitchedOffForTests() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    void sharedClockIsAvailableForTheStores() {
        assertThat(context.getBean(Clock.class)).isNotNull();
    }
}
