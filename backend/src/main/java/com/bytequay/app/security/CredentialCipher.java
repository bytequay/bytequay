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

import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and decrypts stored credentials using AES-256-GCM with a local
 * master key. The master key is a 32-byte random blob stored at
 * {@code ~/Library/Application Support/ByteQuay/credentials.key} with 0600
 * permissions. If the file doesn't exist it is generated on first use.
 *
 * <p>Storage format for each ciphertext is {@code base64(IV || ciphertext || tag)}
 * — a 12-byte random IV prefix followed by the GCM-encrypted payload (with
 * 16-byte tag appended by the GCM provider).
 */
@Component
public class CredentialCipher
{
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32; // AES-256
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_FILENAME = "credentials.key";

    private final SecretKeySpec masterKey;
    private final SecureRandom rng = new SecureRandom();

    public CredentialCipher()
    {
        this.masterKey = loadOrCreateMasterKey(defaultKeyPath());
    }

    /** For tests — inject an explicit key path and file system state. */
    CredentialCipher(Path keyPath)
    {
        this.masterKey = loadOrCreateMasterKey(keyPath);
    }

    public String encrypt(String plaintext)
    {
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String ciphertextB64)
    {
        try {
            byte[] blob = Base64.getDecoder().decode(ciphertextB64);
            if (blob.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[blob.length - IV_BYTES];
            System.arraycopy(blob, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    /**
     * Produces a masked preview of a secret suitable for UI display.
     * Shows the first 4 and last 4 characters of the raw value with bullets
     * in between — e.g. {@code ghp_•••••xyz9}. Short values are fully masked.
     */
    public static String preview(String raw)
    {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= 8) {
            return "•".repeat(trimmed.length());
        }
        return trimmed.substring(0, 4) + "•••••" + trimmed.substring(trimmed.length() - 4);
    }

    private static Path defaultKeyPath()
    {
        String home = System.getProperty("user.home");
        return Paths.get(home, "Library", "Application Support", "ByteQuay", KEY_FILENAME);
    }

    private static SecretKeySpec loadOrCreateMasterKey(Path path)
    {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                byte[] key = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(key);
                Files.write(path, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                tryRestrictPermissions(path);
                log.info("Generated new credential master key at {}", path);
                return new SecretKeySpec(key, "AES");
            }
            byte[] key = Files.readAllBytes(path);
            if (key.length != KEY_BYTES) {
                throw new IllegalStateException("credentials.key is " + key.length + " bytes; expected " + KEY_BYTES);
            }
            return new SecretKeySpec(key, "AES");
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to read or create credential master key at " + path, e);
        }
    }

    private static void tryRestrictPermissions(Path path)
    {
        try {
            Files.setPosixFilePermissions(path, ImmutableSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (Windows); best-effort only.
            log.debug("Could not restrict credentials.key permissions: {}", e.getMessage());
        }
    }
}
