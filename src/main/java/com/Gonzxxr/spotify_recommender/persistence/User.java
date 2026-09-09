package com.Gonzxxr.spotify_recommender.persistence;


import com.Gonzxxr.spotify_recommender.security.EncryptedStringConverter;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "users")
@Getter @Setter
@AllArgsConstructor
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    private Long userId;
    @NotBlank
    @Column(unique = true)
    private String spotifyUserId;
    private String displayName;
    private String playlistId;
    private String lastSyncedSnapshotId;
    @NotBlank
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String accessToken;
    @NotBlank
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String refreshToken;
    @NotNull
    private Instant tokenExpiresAt;
    private Instant createdAt;

}
