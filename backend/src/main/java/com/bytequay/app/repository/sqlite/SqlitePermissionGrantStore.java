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
import com.bytequay.app.repository.PermissionGrantStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqlitePermissionGrantStore
        implements PermissionGrantStore
{
    private final PermissionGrantJpaRepository repo;

    public SqlitePermissionGrantStore(PermissionGrantJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public List<PermissionGrant> findGlobal()
    {
        return repo.findByScopeKindAndScopeIdIsNull("global").stream()
                .map(SqlitePermissionGrantStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<PermissionGrant> findForScope(String scopeKind, String scopeId)
    {
        if (scopeKind == null || scopeId == null || scopeId.isBlank()) {
            return List.of();
        }
        return repo.findByScopeKindAndScopeId(scopeKind, scopeId).stream()
                .map(SqlitePermissionGrantStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
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

    @Override
    @Transactional
    public void delete(long id)
    {
        repo.deleteById(id);
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
