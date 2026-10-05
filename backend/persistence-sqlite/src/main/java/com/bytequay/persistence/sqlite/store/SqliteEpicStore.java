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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.Epic;
import com.bytequay.core.store.EpicStore;
import com.bytequay.persistence.sqlite.entity.EpicEntity;
import com.bytequay.persistence.sqlite.repository.EpicJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteEpicStore
        implements EpicStore
{
    private final EpicJpaRepository epics;

    public SqliteEpicStore(EpicJpaRepository epics)
    {
        this.epics = requireNonNull(epics, "epics is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Epic> find(String id)
    {
        return epics.findById(id).map(EpicEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Epic> list(String workspaceId)
    {
        return epics.findByWorkspaceId(workspaceId).stream().map(EpicEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(Epic epic)
    {
        epics.save(new EpicEntity(epic));
    }

    @Override
    @Transactional
    public void delete(String id)
    {
        epics.deleteById(id);
    }
}
