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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;

import java.util.List;
import java.util.Optional;

/**
 * Persists encrypted credentials. Display-safe {@link Credential} records are
 * returned from read methods; the plaintext value is only available via the
 * {@code getSecret} methods.
 *
 * <p>Each row is uniquely identified by the triple (type, name, instanceName).
 * {@code instanceName} lets multiple keys coexist for the same provider —
 * defaults to {@code "default api"} for callers that don't pick.
 *
 * <p>Convenience read methods that omit {@code instanceName} return the
 * earliest-created instance (lowest id), matching what resolvers / reviewers
 * want when they simply need "any usable secret for this provider".
 */
public interface CredentialStore
{
    String DEFAULT_INSTANCE_NAME = "default api";

    List<Credential> findAll();

    List<Credential> findByType(CredentialType type);

    /** All instances for (type, name); empty when none exist. */
    List<Credential> findByTypeAndName(CredentialType type, String name);

    /** Earliest-created instance for (type, name). */
    Optional<Credential> find(CredentialType type, String name);

    /** Exact lookup. */
    Optional<Credential> find(CredentialType type, String name, String instanceName);

    /** Resolve the default instance for (type, name). Falls back to
     *  the earliest-created row only when V84's backfill hasn't run
     *  (defensive path — production rows always carry a default). */
    Optional<Credential> findDefault(CredentialType type, String name);

    /**
     * Mark {@code (type, name, instanceName)} as the group's default,
     * clearing the previous default in the same transaction. No-op
     * (returns the matching row) when the targeted row is already
     * the default. Throws when the targeted row doesn't exist.
     */
    Credential setDefault(CredentialType type, String name, String instanceName);

    /**
     * Default-instance decrypted value for (type, name) — used by
     * reviewers / PAT resolvers that name only the provider/host.
     * Falls back to the earliest-created instance on legacy rows that
     * pre-date V84's backfill.
     */
    Optional<String> getSecret(CredentialType type, String name);

    /** Exact decrypted value. */
    Optional<String> getSecret(CredentialType type, String name, String instanceName);

    /** Upserts by (type, name, instanceName). MCP rows pass a JSON
     *  config blob through {@code configJson} (transport / authKind
     *  / serverUrl / envVarName); other kinds leave it null. */
    Credential upsert(
            CredentialType type,
            String name,
            String instanceName,
            String rawValue,
            String label,
            String notes,
            String configJson);

    void delete(CredentialType type, String name, String instanceName);
}
