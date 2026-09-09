package com.Gonzxxr.spotify_recommender.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.Gonzxxr.spotify_recommender.persistence.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findBySpotifyUserId(String spotifyUserId);
}
