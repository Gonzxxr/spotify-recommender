package com.Gonzxxr.spotify_recommender.recommendation;

import com.Gonzxxr.spotify_recommender.auth.CurrentUserResolver;
import com.Gonzxxr.spotify_recommender.auth.SessionStore;
import com.Gonzxxr.spotify_recommender.persistence.Recommendation;
import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.RecommendationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/recommendations")
public class RecommendationController {

    private static final Duration SHOWN_COOLDOWN = Duration.ofHours(1);
    private static final int PAGE_SIZE = 10;

    private final CurrentUserResolver currentUserResolver;
    private final RecommendationRepository recommendationRepository;

    public RecommendationController(CurrentUserResolver currentUserResolver, RecommendationRepository recommendationRepository) {
        this.currentUserResolver = currentUserResolver;
        this.recommendationRepository = recommendationRepository;
    }

    @GetMapping
    public ResponseEntity<List<RecommendationResponse>> getRecommendations(
            @CookieValue(value = SessionStore.COOKIE_NAME, required = false) String sessionId) {
        return currentUserResolver.resolve(sessionId)
                .map(user -> user.getPlaylistId() == null
                        ? ResponseEntity.notFound().<List<RecommendationResponse>>build()
                        : ResponseEntity.ok(toRecommendations(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private List<RecommendationResponse> toRecommendations(User user) {
        Instant shownCutoff = Instant.now().minus(SHOWN_COOLDOWN);
        List<Recommendation> toShow = recommendationRepository.findNextToShow(
                user, user.getPlaylistId(), shownCutoff, PageRequest.of(0, PAGE_SIZE));

        Instant now = Instant.now();
        toShow.forEach(recommendation -> recommendation.setShownAt(now));
        recommendationRepository.saveAll(toShow);

        return toShow.stream()
                .map(RecommendationResponse::from)
                .toList();
    }
}
