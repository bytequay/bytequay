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

import com.bytequay.core.hosting.HostingCredential;
import com.bytequay.core.store.HostingCredentialStore;
import com.bytequay.persistence.sqlite.entity.HostingAccountEntity;
import com.bytequay.persistence.sqlite.repository.HostingAccountJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteHostingCredentialStore
        implements HostingCredentialStore
{
    private final HostingCredentialJpaRepository jpaRepository;
    private final HostingAccountJpaRepository accounts;
    private final CredentialCipher cipher;

    public SqliteHostingCredentialStore(HostingCredentialJpaRepository jpaRepository, HostingAccountJpaRepository accounts, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    public Optional<HostingCredential> find(long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        return jpaRepository.findById(accountId)
                .filter(entity -> entity.getCiphertext() != null)
                .map(entity -> new HostingCredential(accountId, entity.getGeneration(), cipher.decrypt(entity.getCiphertext())));
    }

    @Override
    public long generation(long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        return jpaRepository.findById(accountId).map(HostingCredentialEntity::getGeneration).orElse(0L);
    }

    @Override
    @Transactional
    public HostingCredential save(long accountId, String token)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        String secret = checkText(requireNonNull(token, "token is null").trim(), "token");
        HostingCredentialEntity entity = change(accountId, cipher.encrypt(secret));
        return new HostingCredential(accountId, entity.getGeneration(), secret);
    }

    @Override
    @Transactional
    public void delete(long accountId)
    {
        checkArgument(accountId > 0, "accountId must be positive");
        change(accountId, null);
    }

    private HostingCredentialEntity change(long accountId, String ciphertext)
    {
        HostingAccountEntity account = accounts.findById(accountId).orElseThrow(() -> new IllegalStateException("Hosting account does not exist"));
        HostingCredentialEntity entity = jpaRepository.findById(accountId).orElseGet(() -> new HostingCredentialEntity(account));
        entity.update(ciphertext);
        return jpaRepository.save(entity);
    }
}
