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

import com.bytequay.app.domain.CredentialType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface CredentialJpaRepository
        extends JpaRepository<CredentialEntity, Long>
{
    /** Exact lookup of a single instance. */
    Optional<CredentialEntity> findByTypeAndNameAndInstanceName(
            CredentialType type, String name, String instanceName);

    /** All instances for a (type, name) — multiple AI keys for the same provider. */
    List<CredentialEntity> findByTypeAndNameOrderByIdAsc(CredentialType type, String name);

    /**
     * Lowest-id (earliest-created) instance for (type, name). Used as
     * a fallback when no default is set (legacy installs that pre-date
     * V84) — the migration backfills the same row so reads converge.
     */
    Optional<CredentialEntity> findFirstByTypeAndNameOrderByIdAsc(CredentialType type, String name);

    /** The single default instance for (type, name), if any. The
     *  partial unique index in V84 guarantees at most one. */
    Optional<CredentialEntity> findByTypeAndNameAndIsDefault(
            CredentialType type, String name, int isDefault);

    List<CredentialEntity> findByType(CredentialType type);
}
