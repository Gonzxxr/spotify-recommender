package com.Gonzxxr.spotify_recommender.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "recommendation",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_user_recommended_track",
                columnNames = {"user_id", "recommended_track_id", "source_track_id"}
        )
)
@Getter @Setter
@AllArgsConstructor
@NoArgsConstructor
public class Recommendation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recommendationId;
    @NotBlank
    private String recommendedTrackId;
    private String playlistId;
    @ManyToOne(fetch = FetchType.LAZY)
    @NotNull
    @JoinColumn(name = "user_id")
    private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @NotNull
    @JoinColumn(name = "source_track_id")
    private TrackSeen sourceTrack;
    private String recommendedTrackName;
    private String artistName;
    private String albumImageUrl;
    @NotNull
    private Double matchScore;
    private String genre;
    private Instant createdAt;
    private Instant shownAt;

}
