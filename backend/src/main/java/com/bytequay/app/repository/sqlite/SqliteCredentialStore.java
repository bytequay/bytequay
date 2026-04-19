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
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.security.CredentialCipher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCredentialStore
        implements CredentialStore
{
    private final CredentialJpaRepository jpaRepository;
    private final CredentialCipher cipher;

    /**
     * In-memory cache of decrypted secrets keyed by "TYPE/name/instanceName"
     * (or "TYPE/name/*" for the "earliest match" overload). Reads use the
     * cache to skip the DB entirely on the hot path — the sync job resolves
     * the PAT for every per-PR detail fetch via this method, and doing a DB
     * write (last_used_at) on every call was producing SQLITE_BUSY_SNAPSHOT
     * under concurrency.
     *
     * <p>Invalidated whenever upsert / delete touches the same key, so the
     * next read sees the new value.
     */
    private final Map<String, String> secretCache = new ConcurrentHashMap<>();

    public SqliteCredentialStore(CredentialJpaRepository jpaRepository, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    public List<Credential> findAll()
    {
        return jpaRepository.findAll().stream()
                .map(SqliteCredentialStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<Credential> findByType(CredentialType type)
    {
        return jpaRepository.findByType(requireNonNull(type, "type is null")).stream()
                .map(SqliteCredentialStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<Credential> findByTypeAndName(CredentialType type, String name)
    {
        return jpaRepository.findByTypeAndNameOrderByIdAsc(
                        requireNonNull(type, "type is null"),
                        requireNonNull(name, "name is null")).stream()
                .map(SqliteCredentialStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public Optional<Credential> find(CredentialType type, String name)
    {
        return jpaRepository.findFirstByTypeAndNameOrderByIdAsc(
                        requireNonNull(type, "type is null"),
                        requireNonNull(name, "name is null"))
                .map(SqliteCredentialStore::toDomain);
    }

    @Override
    public Optional<Credential> find(CredentialType type, String name, String instanceName)
    {
        return jpaRepository.findByTypeAndNameAndInstanceName(
                        requireNonNull(type, "type is null"),
                        requireNonNull(name, "name is null"),
                        requireNonNull(instanceName, "instanceName is null"))
                .map(SqliteCredentialStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSecret(CredentialType type, String name)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        String key = cacheKey(type, name, null);
        String cached = secretCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return jpaRepository.findFirstByTypeAndNameOrderByIdAsc(type, name).map(entity -> {
            String plain = cipher.decrypt(entity.getCiphertext());
            secretCache.put(key, plain);
            return plain;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSecret(CredentialType type, String name, String instanceName)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        requireNonNull(instanceName, "instanceName is null");
        String key = cacheKey(type, name, instanceName);
        String cached = secretCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return jpaRepository.findByTypeAndNameAndInstanceName(type, name, instanceName).map(entity -> {
            String plain = cipher.decrypt(entity.getCiphertext());
            secretCache.put(key, plain);
            return plain;
        });
    }

    @Override
    @Transactional
    public Credential upsert(
            CredentialType type,
            String name,
            String instanceName,
            String rawValue,
            String label,
            String notes)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        requireNonNull(instanceName, "instanceName is null");
        requireNonNull(rawValue, "rawValue is null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("credential name must not be blank");
        }
        if (instanceName.isBlank()) {
            throw new IllegalArgumentException("credential instance name must not be blank");
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("credential value must not be blank");
        }
        CredentialEntity entity = jpaRepository.findByTypeAndNameAndInstanceName(type, name, instanceName)
                .orElseGet(() -> {
                    CredentialEntity fresh = new CredentialEntity();
                    fresh.setType(type);
                    fresh.setName(name);
                    fresh.setInstanceName(instanceName);
                    return fresh;
                });
        entity.setCiphertext(cipher.encrypt(trimmed));
        entity.setPreview(CredentialCipher.preview(trimmed));
        entity.setLabel(label);
        entity.setNotes(notes);
        Credential saved = toDomain(jpaRepository.save(entity));
        // Bust both cache shapes ("/instanceName" and the wildcard "/*") so
        // subsequent reads pick up the new value.
        secretCache.remove(cacheKey(type, name, instanceName));
        secretCache.remove(cacheKey(type, name, null));
        return saved;
    }

    @Override
    @Transactional
    public void delete(CredentialType type, String name, String instanceName)
    {
        requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        requireNonNull(instanceName, "instanceName is null");
        jpaRepository.findByTypeAndNameAndInstanceName(type, name, instanceName).ifPresent(jpaRepository::delete);
        secretCache.remove(cacheKey(type, name, instanceName));
        secretCache.remove(cacheKey(type, name, null));
    }

    private static String cacheKey(CredentialType type, String name, String instanceName)
    {
        return type.name() + "/" + name + "/" + (instanceName == null ? "*" : instanceName);
    }

    private static Credential toDomain(CredentialEntity e)
    {
        return new Credential(
                e.getId(),
                e.getType(),
                e.getName(),
                e.getInstanceName(),
                e.getLabel(),
                e.getPreview(),
                e.getNotes(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getLastUsedAt());
    }
}
