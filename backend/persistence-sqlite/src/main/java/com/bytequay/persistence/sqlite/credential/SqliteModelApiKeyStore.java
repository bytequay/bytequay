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
package com.bytequay.persistence.sqlite.credential;

import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.ModelApiKeyStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteModelApiKeyStore
        implements ModelApiKeyStore
{
    private final ModelApiKeyJpaRepository jpaRepository;
    private final CredentialCipher cipher;

    public SqliteModelApiKeyStore(ModelApiKeyJpaRepository jpaRepository, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelApiKey> findAll()
    {
        return jpaRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(entity -> new ModelApiKey(entity.getAlias(), entity.getProvider(), entity.getCreatedAt()))
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findSecret(String alias)
    {
        return jpaRepository.findById(alias)
                .map(entity -> cipher.decrypt(entity.getCiphertext()));
    }

    @Override
    @Transactional
    public void save(ModelApiKey key, String secret)
    {
        requireNonNull(key, "key is null");
        String trimmed = requireNonNull(secret, "secret is null").trim();
        checkArgument(!trimmed.isEmpty(), "secret must not be blank");
        checkArgument(!jpaRepository.existsById(key.alias()), "alias already exists");
        jpaRepository.save(new ModelApiKeyEntity(
                key.alias(), key.provider(), cipher.encrypt(trimmed), key.createdAt()));
    }

    @Override
    @Transactional
    public void delete(String alias)
    {
        jpaRepository.deleteById(alias);
    }
}
