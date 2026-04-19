/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCredentialCipher
{
    @Test
    void testEncryptDecryptRoundtripRecoversOriginal(@TempDir Path dir)
    {
        CredentialCipher cipher = new CredentialCipher(dir.resolve("credentials.key"));
        String secret = "ghp_1234567890abcdefghij";

        String encrypted = cipher.encrypt(secret);

        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void testEncryptProducesDifferentCiphertextEachTime(@TempDir Path dir)
    {
        CredentialCipher cipher = new CredentialCipher(dir.resolve("credentials.key"));
        String a = cipher.encrypt("same-input");
        String b = cipher.encrypt("same-input");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void testMasterKeyFileIsCreatedOnFirstUse(@TempDir Path dir)
    {
        Path keyPath = dir.resolve("credentials.key");
        assertThat(Files.exists(keyPath)).isFalse();

        new CredentialCipher(keyPath);

        assertThat(Files.exists(keyPath)).isTrue();
    }

    @Test
    void testSecondCipherWithSameKeyFileCanDecrypt(@TempDir Path dir)
    {
        Path keyPath = dir.resolve("credentials.key");
        CredentialCipher first = new CredentialCipher(keyPath);
        String encrypted = first.encrypt("my secret");

        CredentialCipher second = new CredentialCipher(keyPath);

        assertThat(second.decrypt(encrypted)).isEqualTo("my secret");
    }

    @Test
    void testDecryptWithDifferentKeyFails(@TempDir Path dir)
    {
        Path keyA = dir.resolve("a.key");
        Path keyB = dir.resolve("b.key");
        CredentialCipher cipherA = new CredentialCipher(keyA);
        CredentialCipher cipherB = new CredentialCipher(keyB);
        String encrypted = cipherA.encrypt("secret");

        assertThatThrownBy(() -> cipherB.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    void testPreviewShowsFirst4AndLast4WithBulletsInBetween()
    {
        assertThat(CredentialCipher.preview("ghp_1234567890abcdefghij"))
                .isEqualTo("ghp_•••••ghij");
    }

    @Test
    void testPreviewShortValueIsFullyMasked()
    {
        assertThat(CredentialCipher.preview("abc")).isEqualTo("•••");
        assertThat(CredentialCipher.preview("12345678")).isEqualTo("••••••••");
    }

    @Test
    void testPreviewEmptyAndNull()
    {
        assertThat(CredentialCipher.preview(null)).isEmpty();
        assertThat(CredentialCipher.preview("")).isEmpty();
    }
}
