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

import com.bytequay.app.domain.PermissionGrant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Read/write access to {@code permission_grant} rows. The
 * PermissionResolver only reads (per-scope lookups while walking the
 * cascade); create / delete exist so a settings surface can manage
 * grants later.
 */
@Repository
public class PermissionGrantStore
{
    private final PermissionGrantJpaRepository repo;

    public PermissionGrantStore(PermissionGrantJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    /** Grants attached to the global scope (scope_id is null). */
    public List<PermissionGrant> findGlobal()
    {
        return repo.findByScopeKindAndScopeIdIsNull("global").stream()
                .map(PermissionGrantStore::toDomain)
                .collect(toImmutableList());
    }

    /** Grants attached to a narrower scope, keyed by its id. */
    public List<PermissionGrant> findForScope(String scopeKind, String scopeId)
    {
        if (scopeKind == null || scopeId == null || scopeId.isBlank()) {
            return List.of();
        }
        return repo.findByScopeKindAndScopeId(scopeKind, scopeId).stream()
                .map(PermissionGrantStore::toDomain)
                .collect(toImmutableList());
    }

    @Transactional
    /** Insert a grant. Returns the persisted row. */
    public PermissionGrant create(
            String scopeKind,
            String scopeId,
            String capability,
            String mode,
            String paramsJson)
    {
        PermissionGrantEntity e = new PermissionGrantEntity();
        e.setScopeKind(scopeKind);
        e.setScopeId(scopeId == null || scopeId.isBlank() ? null : scopeId);
        e.setCapability(capability);
        e.setMode(mode);
        e.setParamsJson(paramsJson);
        return toDomain(repo.save(e));
    }

    @Transactional
    /** Hard-delete by id. No-op when the id doesn't exist. */
    public void delete(long id)
    {
        repo.deleteById(id);
    }

    @Transactional
    /** Delete every grant at a narrower scope (workspace/thread/task). Returns the count removed. */
    public int deleteForScope(String scopeKind, String scopeId)
    {
        if (scopeKind == null || scopeId == null || scopeId.isBlank()) {
            return 0;
        }
        return (int) repo.deleteByScopeKindAndScopeId(scopeKind, scopeId);
    }

    private static PermissionGrant toDomain(PermissionGrantEntity e)
    {
        return new PermissionGrant(
                e.getId(),
                e.getScopeKind(),
                e.getScopeId(),
                e.getCapability(),
                e.getMode(),
                e.getParamsJson(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
