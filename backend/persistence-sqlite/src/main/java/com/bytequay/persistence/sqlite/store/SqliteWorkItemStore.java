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

import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.persistence.sqlite.entity.WorkItemEntity;
import com.bytequay.persistence.sqlite.entity.WorkItemIdentityEntity;
import com.bytequay.persistence.sqlite.repository.WorkItemIdentityJpaRepository;
import com.bytequay.persistence.sqlite.repository.WorkItemJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteWorkItemStore
        implements WorkItemStore
{
    private final WorkItemJpaRepository items;
    private final WorkItemIdentityJpaRepository identities;

    public SqliteWorkItemStore(WorkItemJpaRepository items, WorkItemIdentityJpaRepository identities)
    {
        this.items = requireNonNull(items, "items is null");
        this.identities = requireNonNull(identities, "identities is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkItem> list(String workspaceId)
    {
        return items.findByWorkspaceId(workspaceId).stream().map(WorkItemEntity::toDomain)
                .sorted(Comparator.comparing(WorkItem::createdAt).thenComparing(WorkItem::id)).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItem> find(String id)
    {
        return items.findById(id).map(WorkItemEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItem> findByIdentity(String identity)
    {
        return identities.findById(identity).flatMap(owner -> find(owner.getWorkItemId()));
    }

    @Override
    @Transactional
    public void insert(WorkItem item)
    {
        checkArgument(item.revision() == 0, "new item must have revision zero");
        items.saveAndFlush(new WorkItemEntity(item));
        reserve(item);
    }

    @Override
    @Transactional
    public boolean update(WorkItem item, long expectedRevision)
    {
        checkArgument(item.revision() == expectedRevision + 1, "invalid next revision");
        if (items.replace(item.id(), item, item.revision(), expectedRevision) != 1) {
            return false;
        }
        reserve(item);
        return true;
    }

    @Override
    @Transactional
    public void delete(WorkItem item)
    {
        identities.deleteByWorkItemId(item.id());
        items.deleteById(item.id());
    }

    private void reserve(WorkItem item)
    {
        for (WorkItemLink link : item.links()) {
            // A PR may explicitly cover several outcomes. Sources and execution attempts have one owner.
            if (link.reference().kind() == GoalItemKind.PULL_REQUEST) {
                continue;
            }
            Optional<WorkItemIdentityEntity> existing = identities.findById(link.identity());
            if (existing.isPresent()) {
                checkState(existing.orElseThrow().getWorkItemId().equals(item.id()), "Source or task already belongs to another work item");
            }
            else {
                identities.saveAndFlush(new WorkItemIdentityEntity(link.identity(), item.id()));
            }
        }
    }
}
