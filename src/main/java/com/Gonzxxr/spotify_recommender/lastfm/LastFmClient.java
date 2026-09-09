package com.Gonzxxr.spotify_recommender.lastfm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

@Service
public class LastFmClient {

    private static final Logger log = LoggerFactory.getLogger(LastFmClient.class);
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";

    private final LastFmProperties properties;
    private final RestClient restClient = RestClient.create();

    public LastFmClient(LastFmProperties properties) {
        this.properties = properties;
    }

    public List<SimilarTrack> getSimilarTracks(String artist, String track, int limit) {
        String url = UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("method", "track.getsimilar")
                .queryParam("artist", artist)
                .queryParam("track", track)
                .queryParam("api_key", properties.apiKey())
                .queryParam("format", "json")
                .queryParam("limit", limit)
                .encode()
                .build()
                .toUriString();

        try {
            LastFmSimilarTracksResponse response = restClient.get().uri(url).retrieve().body(LastFmSimilarTracksResponse.class);
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
        String url = UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("method", "artist.gettoptags")
                .queryParam("artist", artist)
                .queryParam("api_key", properties.apiKey())
                .queryParam("format", "json")
                .encode()
                .build()
                .toUriString();

        try {
            LastFmTopTagsResponse response = restClient.get().uri(url).retrieve().body(LastFmTopTagsResponse.class);
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
