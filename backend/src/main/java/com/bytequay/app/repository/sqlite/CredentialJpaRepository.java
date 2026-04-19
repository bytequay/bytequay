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
     * Lowest-id (earliest-created) instance for (type, name). Used by
     * resolvers and reviewers that don't care which instance is active —
     * they pick whichever was created first as the canonical one.
     */
    Optional<CredentialEntity> findFirstByTypeAndNameOrderByIdAsc(CredentialType type, String name);

    List<CredentialEntity> findByType(CredentialType type);
}
