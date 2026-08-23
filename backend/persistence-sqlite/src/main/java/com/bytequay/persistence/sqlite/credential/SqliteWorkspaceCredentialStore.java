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

import com.bytequay.core.store.WorkspaceCredentialStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteWorkspaceCredentialStore
        implements WorkspaceCredentialStore
{
    private final WorkspaceCredentialJpaRepository jpaRepository;
    private final CredentialCipher cipher;

    public SqliteWorkspaceCredentialStore(WorkspaceCredentialJpaRepository jpaRepository, CredentialCipher cipher)
    {
        this.jpaRepository = requireNonNull(jpaRepository, "jpaRepository is null");
        this.cipher = requireNonNull(cipher, "cipher is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSecret(String workspaceId)
    {
        return jpaRepository.findById(workspaceId)
                .map(entity -> cipher.decrypt(entity.getCiphertext()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getLogin(String workspaceId)
    {
        return jpaRepository.findById(workspaceId).map(WorkspaceCredentialEntity::getLogin);
    }

    @Override
    @Transactional
    public void save(String workspaceId, String token, String login)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        String trimmed = requireNonNull(token, "token is null").trim();
        checkArgument(!trimmed.isEmpty(), "credential value must not be blank");
        requireNonNull(login, "login is null");

        WorkspaceCredentialEntity entity = jpaRepository.findById(workspaceId)
                .orElseGet(() -> new WorkspaceCredentialEntity(workspaceId, cipher.encrypt(trimmed), login));
        entity.setCiphertext(cipher.encrypt(trimmed));
        entity.setLogin(login);
        jpaRepository.save(entity);
    }

    @Override
    @Transactional
    public void delete(String workspaceId)
    {
        jpaRepository.deleteById(workspaceId);
    }
}
