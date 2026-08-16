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

import com.bytequay.core.store.CredentialStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCredentialStore
        implements CredentialStore
{
    private final CredentialJpaRepository jpaRepository;
    private final CredentialCipher cipher;
    private volatile String cachedSecret;

    public SqliteCredentialStore(CredentialJpaRepository jpaRepository, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    public boolean exists()
    {
        return jpaRepository.findFirstByOrderByIdAsc().isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSecret()
    {
        if (cachedSecret != null) {
            return Optional.of(cachedSecret);
        }
        return jpaRepository.findFirstByOrderByIdAsc().map(entity -> {
            cachedSecret = cipher.decrypt(entity.getCiphertext());
            return cachedSecret;
        });
    }

    @Override
    public Optional<String> source()
    {
        return jpaRepository.findFirstByOrderByIdAsc().map(CredentialEntity::getSource);
    }

    @Override
    @Transactional
    public void save(String token, String source)
    {
        String trimmed = requireNonNull(token, "token is null").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("credential value must not be blank");
        }

        CredentialEntity entity = jpaRepository.findFirstByOrderByIdAsc().orElseGet(CredentialEntity::new);
        entity.setCiphertext(cipher.encrypt(trimmed));
        entity.setSource(source);
        jpaRepository.save(entity);
        cachedSecret = null;
    }
}
