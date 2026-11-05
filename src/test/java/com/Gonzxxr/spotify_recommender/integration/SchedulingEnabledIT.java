package com.Gonzxxr.spotify_recommender.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the production default: with the property absent (matchIfMissing), the @Scheduled jobs
 * must be registered, otherwise the app would silently stop syncing and recommending.
 */
@SpringBootTest(properties = "app.scheduling.enabled=true")
@ActiveProfiles("test")
@Import(ExternalApisTestConfig.class)
class SchedulingEnabledIT {

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestContainer.register(registry);
    }

    @Test
    void scheduledJobsAreRegisteredWhenEnabled() {
        ScheduledAnnotationBeanPostProcessor processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);

        assertThat(processor.getScheduledTasks()).hasSize(2);
    }
}
