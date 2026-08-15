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
import com.bytequay.app.repository.CredentialStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * CRUD over the credentials vault.
 *
 * <p>Each credential is keyed by (type, name, instanceName); convenience
 * overloads omit {@code instanceName} and resolve to the default instance.
 */
@Service
public class CredentialService
{
    public static final String GITHUB_ACCOUNT_NAME = "github";

    private final CredentialStore credentialStore;

    public CredentialService(CredentialStore credentialStore)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
    }

    public List<Credential> list()
    {
        return credentialStore.findAll();
    }

    public List<Credential> listByType(CredentialType type)
    {
        return credentialStore.findByType(type);
    }

    /** All instances of (type, name), in insertion order. */
    public List<Credential> listByTypeAndName(CredentialType type, String name)
    {
        return credentialStore.findByTypeAndName(type, name);
    }

    /** Earliest-created instance for (type, name). */
    public Optional<Credential> get(CredentialType type, String name)
    {
        return credentialStore.find(type, name);
    }

    /** Exact lookup. */
    public Optional<Credential> get(CredentialType type, String name, String instanceName)
    {
        return credentialStore.find(type, name, instanceName);
    }

    /** Resolve the user's default instance for (type, name). */
    public Optional<Credential> getDefault(CredentialType type, String name)
    {
        return credentialStore.findDefault(type, name);
    }

    /** Promote {@code (type, name, instanceName)} to the group's
     *  default — the store clears the previous default in the same
     *  transaction so the single-default invariant holds. */
    public Credential setDefault(CredentialType type, String name, String instanceName)
    {
        return credentialStore.setDefault(type, name, instanceName);
    }

    /** Earliest-created decrypted value for (type, name). */
    public Optional<String> getSecret(CredentialType type, String name)
    {
        return credentialStore.getSecret(type, name);
    }

    /** Exact decrypted value. */
    public Optional<String> getSecret(CredentialType type, String name, String instanceName)
    {
        return credentialStore.getSecret(type, name, instanceName);
    }

    public Credential upsert(
            CredentialType type,
            String name,
            String instanceName,
            String rawValue,
            String label,
            String notes)
    {
        return upsert(type, name, instanceName, rawValue, label, notes, null);
    }

    /** Upsert with optional source metadata, used for GitHub CLI imports. */
    public Credential upsert(
            CredentialType type,
            String name,
            String instanceName,
            String rawValue,
            String label,
            String notes,
            String configJson)
    {
        return credentialStore.upsert(type, name, instanceName, rawValue, label, notes, configJson);
    }

    public void delete(CredentialType type, String name, String instanceName)
    {
        credentialStore.delete(type, name, instanceName);
    }
}
