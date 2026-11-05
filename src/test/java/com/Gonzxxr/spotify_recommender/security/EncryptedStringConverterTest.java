package com.Gonzxxr.spotify_recommender.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedStringConverterTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(Arrays.copyOf(new byte[]{1}, 32));

    private final EncryptedStringConverter converter = new EncryptedStringConverter(KEY);

    @Test
    void roundTripsPlainText() {
        String encrypted = converter.convertToDatabaseColumn("BQD-my-spotify-token");

        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo("BQD-my-spotify-token");
    }

    @Test
    void roundTripsUnicodeAndLongValues() {
        String value = "ñandú ♪ 🎵 " + "x".repeat(2000);

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(value))).isEqualTo(value);
    }

    @Test
    void roundTripsEmptyString() {
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(""))).isEmpty();
    }

    @Test
    void nullStaysNullInBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void storedValueDoesNotLeakThePlaintext() {
        String encrypted = converter.convertToDatabaseColumn("super-secret-token");

        assertThat(encrypted).doesNotContain("super-secret-token");
        assertThat(new String(Base64.getDecoder().decode(encrypted), StandardCharsets.ISO_8859_1))
                .doesNotContain("super-secret-token");
    }

    @Test
    void usesAFreshIvEveryTime() {
        Set<String> outputs = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            outputs.add(converter.convertToDatabaseColumn("same-input"));
        }

        assertThat(outputs).hasSize(50);
    }

    @Test
    void outputIsIvPlusCiphertextPlusTag() {
        byte[] raw = Base64.getDecoder().decode(converter.convertToDatabaseColumn("abc"));

        // 12-byte IV + 3 bytes of ciphertext + 16-byte GCM tag
        assertThat(raw).hasSize(12 + 3 + 16);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] raw = Base64.getDecoder().decode(converter.convertToDatabaseColumn("token"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt");
    }

    @Test
    void valueEncryptedWithAnotherKeyIsRejected() {
        String encrypted = new EncryptedStringConverter(OTHER_KEY).convertToDatabaseColumn("token");

        assertThatThrownBy(() -> converter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameKeyInAnotherInstanceCanDecrypt() {
        String encrypted = converter.convertToDatabaseColumn("token");

        assertThat(new EncryptedStringConverter(KEY).convertToEntityAttribute(encrypted)).isEqualTo("token");
    }

    @Test
    void invalidBase64InputFailsLoudly() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("%%% not base64 %%%"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyOfWrongLengthIsRejectedOnFirstUse() {
        EncryptedStringConverter badKey = new EncryptedStringConverter(Base64.getEncoder().encodeToString(new byte[10]));

        assertThatThrownBy(() -> badKey.convertToDatabaseColumn("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encrypt");
    }

    @Test
    void keyThatIsNotBase64IsRejectedAtStartup() {
        assertThatThrownBy(() -> new EncryptedStringConverter("not base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
