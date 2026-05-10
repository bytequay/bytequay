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

import com.bytequay.app.domain.SlackInboxKind;
import com.bytequay.app.domain.SlackMessage;
import com.bytequay.app.repository.SlackMessageStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSlackMessageStore
        implements SlackMessageStore
{
    private final SlackMessageJpaRepository repo;

    public SqliteSlackMessageStore(SlackMessageJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    @Transactional
    public void insertIfAbsent(List<SlackMessage> messages)
    {
        for (SlackMessage m : messages) {
            SlackMessageEntity.SlackMessageKey key =
                    new SlackMessageEntity.SlackMessageKey(m.workspaceId(), m.channelId(), m.ts());
            if (repo.existsById(key)) {
                continue;
            }
            SlackMessageEntity entity = new SlackMessageEntity();
            entity.setId(key);
            entity.setUserId(m.userId());
            entity.setText(m.text());
            entity.setThreadTs(m.threadTs());
            entity.setHasAtYou(m.hasAtYou());
            entity.setInboxKind(m.inboxKind().toDb());
            entity.setRawJson(m.rawJson());
            entity.setFetchedAt(m.fetchedAt());
            repo.save(entity);
        }
    }

    @Override
    public List<SlackMessage> findByChannel(String workspaceId, String channelId)
    {
        return repo.findByIdWorkspaceIdAndIdChannelIdOrderByIdTsDesc(workspaceId, channelId).stream()
                .map(SqliteSlackMessageStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<SlackMessage> findByInboxKind(String workspaceId, SlackInboxKind kind)
    {
        return repo.findByIdWorkspaceIdAndInboxKindOrderByIdTsDesc(workspaceId, kind.toDb()).stream()
                .map(SqliteSlackMessageStore::toDomain)
                .collect(toImmutableList());
    }

    private static SlackMessage toDomain(SlackMessageEntity entity)
    {
        return new SlackMessage(
                entity.getId().getWorkspaceId(),
                entity.getId().getChannelId(),
                entity.getId().getTs(),
                entity.getUserId(),
                entity.getText(),
                entity.getThreadTs(),
                entity.isHasAtYou(),
                SlackInboxKind.valueOf(entity.getInboxKind().toUpperCase(ROOT)),
                entity.getRawJson(),
                entity.getFetchedAt());
    }
}
