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

import com.bytequay.app.domain.FollowedChannel;
import com.bytequay.app.repository.FollowedChannelStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteFollowedChannelStore
        implements FollowedChannelStore
{
    private final FollowedChannelJpaRepository repo;

    public SqliteFollowedChannelStore(FollowedChannelJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public List<FollowedChannel> findByWorkspace(String workspaceId)
    {
        return repo.findByIdWorkspaceId(workspaceId).stream()
                .map(SqliteFollowedChannelStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void replace(String workspaceId, List<FollowedChannel> channels)
    {
        Instant now = Instant.now();
        Set<String> incomingChannelIds = new HashSet<>();
        for (FollowedChannel channel : channels) {
            incomingChannelIds.add(channel.channelId());
            FollowedChannelEntity.FollowedChannelKey key =
                    new FollowedChannelEntity.FollowedChannelKey(workspaceId, channel.channelId());
            FollowedChannelEntity entity = repo.findById(key).orElseGet(FollowedChannelEntity::new);
            entity.setId(key);
            entity.setChannelName(channel.channelName());
            entity.setPrivate(channel.isPrivate());
            // Preserve the original selected_at on existing rows so the
            // sidebar's "added X ago" affordance (when we ship it) reads as
            // the time the user first picked the channel, not the most
            // recent picker save.
            if (entity.getSelectedAt() == null) {
                entity.setSelectedAt(channel.selectedAt() != null ? channel.selectedAt() : now);
            }
            repo.save(entity);
        }
        // Drop rows the new set doesn't include — that's the "untoggle"
        // path. Done after the upserts so a single-row delete-and-readd
        // can't transiently empty the table on a restart-mid-replace.
        for (FollowedChannelEntity existing : repo.findByIdWorkspaceId(workspaceId)) {
            if (!incomingChannelIds.contains(existing.getId().getChannelId())) {
                repo.delete(existing);
            }
        }
    }

    private static FollowedChannel toDomain(FollowedChannelEntity entity)
    {
        return new FollowedChannel(
                entity.getId().getWorkspaceId(),
                entity.getId().getChannelId(),
                entity.getChannelName(),
                entity.isPrivate(),
                entity.getSelectedAt());
    }
}
