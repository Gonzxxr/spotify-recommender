package com.Gonzxxr.spotify_recommender.playlist;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

@Service
public class PlaylistService {

    private static final String PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists?limit=50";

    private final RestClient restClient;

    public PlaylistService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public List<PlaylistSummary> fetchUserPlaylists(String accessToken, String spotifyUserId) {
        SpotifyOwnedPlaylistsResponse response = restClient.get()
                .uri(PLAYLISTS_URL)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(SpotifyOwnedPlaylistsResponse.class);

        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .filter(raw -> raw.owner() != null && spotifyUserId.equals(raw.owner().id()))
                .map(this::toSummary)
                .toList();
    }

    private PlaylistSummary toSummary(SpotifyRawPlaylist raw) {
        String imageUrl = Optional.ofNullable(raw.images())
                .filter(images -> !images.isEmpty())
                .map(images -> images.get(0).url())
                .orElse(null);
        int trackCount = raw.items() != null ? raw.items().total() : 0;
        return new PlaylistSummary(raw.id(), raw.name(), imageUrl, trackCount);
    }
}
