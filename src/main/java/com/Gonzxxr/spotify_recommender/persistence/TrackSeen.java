package com.Gonzxxr.spotify_recommender.persistence;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(
        name = "track_seen",
        uniqueConstraints = @UniqueConstraint(
                name = "unique_user_playlist_track",
                columnNames = {"user_id", "playlist_id", "spotify_track_id"}
        )
)
@Getter @Setter
@AllArgsConstructor
@NoArgsConstructor
public class TrackSeen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long trackSeenId;
    @ManyToOne(fetch = FetchType.LAZY)
    @NotNull
    @JoinColumn(name = "user_id")
    private User user;
    @NotNull
    @Column(name = "spotify_track_id")
    private String spotifyTrackId;
    private String playlistId;
    private String trackName;
    private String artistName;
    @NotNull
    private Instant addedToPlaylistAt;
    @ColumnDefault("0")
    private int recommendationAttempts;
    private Instant lastAttemptedAt;
}
