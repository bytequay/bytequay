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

import com.bytequay.core.merge.AutoMerge;
import com.bytequay.core.merge.AutoMergeStore;
import com.bytequay.persistence.sqlite.entity.AutoMergeEntity;
import com.bytequay.persistence.sqlite.repository.AutoMergeJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteAutoMergeStore
        implements AutoMergeStore
{
    private final AutoMergeJpaRepository merges;

    public SqliteAutoMergeStore(AutoMergeJpaRepository merges)
    {
        this.merges = requireNonNull(merges, "merges is null");
    }

    @Override
    public Optional<AutoMerge> find(String taskId)
    {
        return merges.findById(taskId).map(AutoMergeEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(AutoMerge autoMerge)
    {
        AutoMergeEntity entity = merges.findById(autoMerge.taskId()).orElseGet(() -> new AutoMergeEntity(autoMerge.taskId()));
        entity.update(autoMerge);
        merges.saveAndFlush(entity);
    }
}
