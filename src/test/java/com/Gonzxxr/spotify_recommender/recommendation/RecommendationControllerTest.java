package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.auth.CurrentUserResolver;
import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.TrackSeen;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationControllerTest {

    private static final Cookie SESSION = new Cookie("SESSION_ID", "sid");

    @Mock
    private CurrentUserResolver resolver;
    @Mock
    private RecommendationRepository repository;

    private MockMvc mvc;
    private User user;
    private TrackSeen source;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(resolver, repository)).build();
        user = TestData.user("spotify-1");
        user.setPlaylistId("pl");
        source = TestData.trackSeen(user, "pl", "src", "Source Song", "Source Artist");
        when(resolver.resolve("sid")).thenReturn(Optional.of(user));
        when(resolver.resolve(null)).thenReturn(Optional.empty());
    }

    @Test
    void requiresALogin() throws Exception {
        mvc.perform(get("/recommendations")).andExpect(status().isUnauthorized());

        verifyNoInteractions(repository);
    }

    @Test
    void isNotFoundUntilThePersonHasPickedAPlaylist() throws Exception {
        user.setPlaylistId(null);

        mvc.perform(get("/recommendations").cookie(SESSION)).andExpect(status().isNotFound());

        verifyNoInteractions(repository);
    }

    @Test
    void returnsTheBatchSortedByMatchScoreDescending() throws Exception {
        Recommendation low = TestData.recommendation(user, source, "low", 0.2);
        Recommendation high = TestData.recommendation(user, source, "high", 0.9);
        Recommendation mid = TestData.recommendation(user, source, "mid", 0.5);
        when(repository.findNextToShow(eq(user), eq("pl"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(low, high, mid));

        mvc.perform(get("/recommendations").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].recommendedTrackId").value("high"))
                .andExpect(jsonPath("$[1].recommendedTrackId").value("mid"))
                .andExpect(jsonPath("$[2].recommendedTrackId").value("low"))
                .andExpect(jsonPath("$[0].sourceTrackName").value("Source Song"))
                .andExpect(jsonPath("$[0].sourceArtistName").value("Source Artist"));
    }

    @Test
    void marksTheReturnedRecommendationsAsShownSoTheyRotateOut() throws Exception {
        Recommendation a = TestData.recommendation(user, source, "a", 0.5);
        Recommendation b = TestData.recommendation(user, source, "b", 0.6);
        when(repository.findNextToShow(any(), any(), any(), any())).thenReturn(List.of(a, b));
        Instant before = Instant.now();

        mvc.perform(get("/recommendations").cookie(SESSION)).andExpect(status().isOk());

        assertThat(a.getShownAt()).isBetween(before, Instant.now());
        assertThat(b.getShownAt()).isBetween(before, Instant.now());
        verify(repository).saveAll(List.of(a, b));
    }

    @Test
    void asksForTenItemsNotShownDuringTheLastHour() throws Exception {
        when(repository.findNextToShow(any(), any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        mvc.perform(get("/recommendations").cookie(SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findNextToShow(eq(user), eq("pl"), cutoff.capture(), page.capture());
        assertThat(Duration.between(cutoff.getValue(), before)).isBetween(Duration.ofMinutes(59), Duration.ofMinutes(61));
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
    }
}
