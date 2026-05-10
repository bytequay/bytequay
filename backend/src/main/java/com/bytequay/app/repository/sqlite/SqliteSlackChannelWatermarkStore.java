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

import com.bytequay.app.domain.SlackChannelWatermark;
import com.bytequay.app.repository.SlackChannelWatermarkStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSlackChannelWatermarkStore
        implements SlackChannelWatermarkStore
{
    private final SlackChannelWatermarkJpaRepository repo;

    public SqliteSlackChannelWatermarkStore(SlackChannelWatermarkJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public Optional<SlackChannelWatermark> find(String workspaceId, String channelId)
    {
        SlackChannelWatermarkEntity.SlackChannelWatermarkKey key =
                new SlackChannelWatermarkEntity.SlackChannelWatermarkKey(workspaceId, channelId);
        return repo.findById(key).map(SqliteSlackChannelWatermarkStore::toDomain);
    }

    @Override
    @Transactional
    public void upsert(SlackChannelWatermark watermark)
    {
        SlackChannelWatermarkEntity.SlackChannelWatermarkKey key =
                new SlackChannelWatermarkEntity.SlackChannelWatermarkKey(
                        watermark.workspaceId(), watermark.channelId());
        SlackChannelWatermarkEntity entity = repo.findById(key).orElseGet(SlackChannelWatermarkEntity::new);
        entity.setId(key);
        entity.setLastTs(watermark.lastTs());
        entity.setLastPolledAt(watermark.lastPolledAt());
        repo.save(entity);
    }

    private static SlackChannelWatermark toDomain(SlackChannelWatermarkEntity entity)
    {
        return new SlackChannelWatermark(
                entity.getId().getWorkspaceId(),
                entity.getId().getChannelId(),
                entity.getLastTs(),
                entity.getLastPolledAt());
    }
}
