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
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.CredentialStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.AppSettingsStore.Key.GITHUB_PAT;
import static com.bytequay.app.repository.CredentialStore.DEFAULT_INSTANCE_NAME;
import static java.util.Objects.requireNonNull;

/**
 * CRUD over the credentials vault, plus a one-time migration of the legacy
 * {@code app_settings.github.pat} row into the new table.
 *
 * <p>Each credential is keyed by (type, name, instanceName); convenience
 * overloads omit {@code instanceName} and resolve to the earliest-created
 * instance, which is what reviewers / the PAT resolver want when they don't
 * care about the specific key.
 */
@Service
public class CredentialService
{
    public static final String GITHUB_ACCOUNT_NAME = "github";

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final CredentialStore credentialStore;
    private final AppSettingsStore appSettingsStore;

    public CredentialService(CredentialStore credentialStore, AppSettingsStore appSettingsStore)
    {
        this.credentialStore = requireNonNull(credentialStore, "credentialStore is null");
        this.appSettingsStore = requireNonNull(appSettingsStore, "appSettingsStore is null");
    }

    @PostConstruct
    public void migrateLegacyPat()
    {
        if (credentialStore.find(CredentialType.ACCOUNT, GITHUB_ACCOUNT_NAME).isPresent()) {
            return;
        }
        appSettingsStore.get(GITHUB_PAT)
                .filter(value -> !value.isBlank())
                .ifPresent(this::migrateLegacyPat);
    }

    public List<Credential> list()
    {
        return credentialStore.findAll();
    }

    public List<Credential> listByType(CredentialType type)
    {
        return credentialStore.findByType(type);
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
        return credentialStore.upsert(type, name, instanceName, rawValue, label, notes);
    }

    public void delete(CredentialType type, String name, String instanceName)
    {
        credentialStore.delete(type, name, instanceName);
    }

    private void migrateLegacyPat(String legacyPat)
    {
        credentialStore.upsert(
                CredentialType.ACCOUNT,
                GITHUB_ACCOUNT_NAME,
                DEFAULT_INSTANCE_NAME,
                legacyPat,
                null,
                "Migrated from legacy app_settings on first boot.");
        appSettingsStore.set(GITHUB_PAT, "");
        log.info("Migrated legacy GitHub PAT from app_settings into credentials table");
    }
}
