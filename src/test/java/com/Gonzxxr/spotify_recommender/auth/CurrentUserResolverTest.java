package com.Gonzxxr.spotify_recommender.auth;

import com.Gonzxxr.spotify_recommender.persistence.User;
import com.Gonzxxr.spotify_recommender.repository.UserRepository;
import com.Gonzxxr.spotify_recommender.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock
    private SessionStore sessionStore;
    @Mock
    private UserRepository userRepository;

    @Test
    void resolvesUserFromSession() {
        User user = TestData.user("u1");
        when(sessionStore.resolve("sid")).thenReturn(Optional.of("u1"));
        when(userRepository.findBySpotifyUserId("u1")).thenReturn(Optional.of(user));

        assertThat(new CurrentUserResolver(sessionStore, userRepository).resolve("sid")).contains(user);
    }

    @Test
    void emptyWhenSessionIsInvalidAndDoesNotTouchTheDatabase() {
        when(sessionStore.resolve("bad")).thenReturn(Optional.empty());

        assertThat(new CurrentUserResolver(sessionStore, userRepository).resolve("bad")).isEmpty();
        verifyNoInteractions(userRepository);
    }

    @Test
    void emptyWhenSessionPointsToAUserThatNoLongerExists() {
        when(sessionStore.resolve("sid")).thenReturn(Optional.of("ghost"));
        when(userRepository.findBySpotifyUserId("ghost")).thenReturn(Optional.empty());

        assertThat(new CurrentUserResolver(sessionStore, userRepository).resolve("sid")).isEmpty();
    }
}
