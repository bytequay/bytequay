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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.security.CredentialCipher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCredentialStore
{
    private static final String LEGACY_INSTANCE_NAME = "default api";

    private final CredentialJpaRepository jpaRepository;
    private final CredentialCipher cipher;
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();

    public SqliteCredentialStore(CredentialJpaRepository jpaRepository, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    public Optional<Credential> find(CredentialType type, String name)
    {
        return findEntity(type, name).map(SqliteCredentialStore::toDomain);
    }

    @Transactional(readOnly = true)
    public Optional<String> getSecret(CredentialType type, String name)
    {
        String key = cacheKey(type, name);
        String cached = secretCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return findEntity(type, name).map(entity -> {
            String plain = cipher.decrypt(entity.getCiphertext());
            secretCache.put(key, plain);
            return plain;
        });
    }

    @Transactional
    public Credential upsert(
            CredentialType type,
            String name,
            String rawValue,
            String label,
            String notes,
            String configJson)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        requireNonNull(rawValue, "rawValue is null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("credential name must not be blank");
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("credential value must not be blank");
        }

        CredentialEntity entity = findEntity(type, name).orElseGet(() -> {
            CredentialEntity fresh = new CredentialEntity();
            fresh.setType(type);
            fresh.setName(name);
            // Existing local databases still require these retired columns.
            fresh.setInstanceName(LEGACY_INSTANCE_NAME);
            fresh.setDefault(true);
            return fresh;
        });
        entity.setCiphertext(cipher.encrypt(trimmed));
        entity.setPreview(CredentialCipher.preview(trimmed));
        entity.setLabel(label);
        entity.setNotes(notes);
        entity.setConfigJson(configJson);
        Credential saved = toDomain(jpaRepository.save(entity));
        secretCache.remove(cacheKey(type, name));
        return saved;
    }

    @Transactional
    public void delete(CredentialType type, String name)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        jpaRepository.deleteByTypeAndName(type, name);
        secretCache.remove(cacheKey(type, name));
    }

    private Optional<CredentialEntity> findEntity(CredentialType type, String name)
    {
        return jpaRepository.findFirstByTypeAndNameOrderByIdAsc(
                requireNonNull(type, "type is null"),
                requireNonNull(name, "name is null"));
    }

    private static String cacheKey(CredentialType type, String name)
    {
        return type.name() + "/" + name;
    }

    private static Credential toDomain(CredentialEntity e)
    {
        return new Credential(
                e.getId(), e.getType(), e.getName(), e.getLabel(), e.getPreview(), e.getNotes(),
                e.getConfigJson(), e.getCreatedAt(), e.getUpdatedAt(), e.getLastUsedAt());
    }
}
