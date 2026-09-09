package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CurrentUserResolver {

    private final SessionStore sessionStore;
    private final UserRepository userRepository;

    public CurrentUserResolver(SessionStore sessionStore, UserRepository userRepository) {
        this.sessionStore = sessionStore;
        this.userRepository = userRepository;
    }

    public Optional<User> resolve(String sessionId) {
        return sessionStore.resolve(sessionId).flatMap(userRepository::findBySpotifyUserId);
    }
}
