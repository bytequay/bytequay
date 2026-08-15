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
package com.bytequay.app.service;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.repository.sqlite.SqliteCredentialStore;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class CredentialService
{
    public static final String GITHUB_ACCOUNT_NAME = "github";

    private final SqliteCredentialStore credentialStore;

    public CredentialService(SqliteCredentialStore credentialStore)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
    }

    public Optional<Credential> get(CredentialType type, String name)
    {
        return credentialStore.find(type, name);
    }

    public Optional<String> getSecret(CredentialType type, String name)
    {
        return credentialStore.getSecret(type, name);
    }

    public Credential upsert(
            CredentialType type,
            String name,
            String rawValue,
            String label,
            String notes)
    {
        return upsert(type, name, rawValue, label, notes, null);
    }

    public Credential upsert(
            CredentialType type,
            String name,
            String rawValue,
            String label,
            String notes,
            String configJson)
    {
        return credentialStore.upsert(type, name, rawValue, label, notes, configJson);
    }

    public void delete(CredentialType type, String name)
    {
        credentialStore.delete(type, name);
    }
}
