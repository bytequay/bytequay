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

import com.bytequay.app.domain.SlackInboxItemState;
import com.bytequay.app.domain.SlackInboxStateRow;
import com.bytequay.app.repository.SlackInboxStateStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSlackInboxStateStore
        implements SlackInboxStateStore
{
    private final SlackInboxStateJpaRepository repo;

    public SqliteSlackInboxStateStore(SlackInboxStateJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public Optional<SlackInboxStateRow> find(String workspaceId, String channelId, String ts)
    {
        return repo.findById(key(workspaceId, channelId, ts))
                .map(SqliteSlackInboxStateStore::toDomain);
    }

    @Override
    public List<SlackInboxStateRow> findActive(String workspaceId)
    {
        return repo.findActive(workspaceId).stream()
                .map(SqliteSlackInboxStateStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void createIfAbsent(String workspaceId, String channelId, String ts)
    {
        SlackInboxStateEntity.SlackInboxStateKey k = key(workspaceId, channelId, ts);
        if (repo.existsById(k)) {
            return;
        }
        SlackInboxStateEntity entity = new SlackInboxStateEntity();
        entity.setId(k);
        entity.setState(SlackInboxItemState.UNREAD.toDb());
        repo.save(entity);
    }

    @Override
    @Transactional
    public void markExpanded(String workspaceId, String channelId, String ts, Instant when)
    {
        repo.findById(key(workspaceId, channelId, ts)).ifPresent(entity -> {
            // First-open stamp wins — re-opens shouldn't reset it. The
            // state flips to EXPANDED only if the row was UNREAD; an
            // already-RESPONDED or BUMPED row keeps its semantic state.
            if (entity.getExpandedAt() == null) {
                entity.setExpandedAt(when);
            }
            if (SlackInboxItemState.UNREAD.toDb().equals(entity.getState())) {
                entity.setState(SlackInboxItemState.EXPANDED.toDb());
            }
            repo.save(entity);
        });
    }

    @Override
    @Transactional
    public void markResponded(String workspaceId, String channelId, String ts, Instant when)
    {
        repo.findById(key(workspaceId, channelId, ts)).ifPresent(entity -> {
            entity.setState(SlackInboxItemState.RESPONDED.toDb());
            entity.setRespondedAt(when);
            // A reply on a previously-bumped item resets the bump signal —
            // we treat it as "user has now weighed in again".
            entity.setBumpedAt(null);
            repo.save(entity);
        });
    }

    @Override
    @Transactional
    public void markArchived(String workspaceId, String channelId, String ts, Instant when)
    {
        repo.findById(key(workspaceId, channelId, ts)).ifPresent(entity -> {
            entity.setArchivedAt(when);
            repo.save(entity);
        });
    }

    @Override
    @Transactional
    public void resurrect(String workspaceId, String channelId, String ts, Instant when)
    {
        repo.findById(key(workspaceId, channelId, ts)).ifPresent(entity -> {
            entity.setArchivedAt(null);
            entity.setBumpedAt(when);
            entity.setState(SlackInboxItemState.BUMPED.toDb());
            repo.save(entity);
        });
    }

    @Override
    public List<SlackInboxStateRow> findRespondedBefore(String workspaceId, Instant threshold)
    {
        return repo.findRespondedBefore(workspaceId, threshold).stream()
                .map(SqliteSlackInboxStateStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void updateState(String workspaceId, String channelId, String ts, SlackInboxItemState state)
    {
        repo.findById(key(workspaceId, channelId, ts)).ifPresent(entity -> {
            entity.setState(state.toDb());
            repo.save(entity);
        });
    }

    private static SlackInboxStateEntity.SlackInboxStateKey key(String workspaceId, String channelId, String ts)
    {
        return new SlackInboxStateEntity.SlackInboxStateKey(workspaceId, channelId, ts);
    }

    private static SlackInboxStateRow toDomain(SlackInboxStateEntity entity)
    {
        return new SlackInboxStateRow(
                entity.getId().getWorkspaceId(),
                entity.getId().getChannelId(),
                entity.getId().getTs(),
                SlackInboxItemState.fromDb(entity.getState()),
                entity.getArchivedAt(),
                entity.getBumpedAt(),
                entity.getRespondedAt(),
                entity.getExpandedAt());
    }
}
