package com.Gonzxxr.spotify_recommender.lastfm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@Service
public class LastFmClient {

    private static final Logger log = LoggerFactory.getLogger(LastFmClient.class);
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";

    private final LastFmProperties properties;
    private final RestClient restClient;

    public LastFmClient(LastFmProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    public List<SimilarTrack> getSimilarTracks(String artist, String track, int limit) {
        // Variables go through RestClient's URI template so each value is percent-encoded exactly once.
        String url = BASE_URL + "?method=track.getsimilar&artist={artist}&track={track}&api_key={apiKey}&format=json&limit={limit}";

        try {
            LastFmSimilarTracksResponse response = restClient.get().uri(url, artist, track, properties.apiKey(), limit).retrieve().body(LastFmSimilarTracksResponse.class);
            if (response == null || response.similarTracks() == null || response.similarTracks().track() == null) {
                return List.of();
            }
            return response.similarTracks().track().stream()
                    .map(t -> new SimilarTrack(t.name(), t.artist() != null ? t.artist().name() : null, parseMatch(t.match())))
                    .toList();
        } catch (RestClientException e) {
            log.warn("Last.fm getSimilarTracks failed for artist='{}' track='{}': {}", artist, track, e.getMessage());
            return List.of();
        }
    }

    public Optional<String> getTopTagForArtist(String artist) {
        String url = BASE_URL + "?method=artist.gettoptags&artist={artist}&api_key={apiKey}&format=json";

        try {
            LastFmTopTagsResponse response = restClient.get().uri(url, artist, properties.apiKey()).retrieve().body(LastFmTopTagsResponse.class);
            if (response == null || response.topTags() == null || response.topTags().tag() == null || response.topTags().tag().isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(response.topTags().tag().get(0).name());
        } catch (RestClientException e) {
            log.warn("Last.fm getTopTagForArtist failed for artist='{}': {}", artist, e.getMessage());
            return Optional.empty();
        }
    }

    private double parseMatch(String match) {
        if (match == null) return 0.0;
        try {
            return Double.parseDouble(match);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
