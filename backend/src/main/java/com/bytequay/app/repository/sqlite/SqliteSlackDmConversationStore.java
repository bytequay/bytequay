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

import com.bytequay.app.domain.SlackDmConversation;
import com.bytequay.app.repository.SlackDmConversationStore;
import com.google.common.base.Splitter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSlackDmConversationStore
        implements SlackDmConversationStore
{
    private static final Splitter PEER_SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();

    private final SlackDmConversationJpaRepository repo;

    public SqliteSlackDmConversationStore(SlackDmConversationJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public List<SlackDmConversation> findByWorkspace(String workspaceId)
    {
        return repo.findByIdWorkspaceId(workspaceId).stream()
                .map(SqliteSlackDmConversationStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void replace(String workspaceId, List<SlackDmConversation> conversations)
    {
        Set<String> incoming = new HashSet<>();
        for (SlackDmConversation c : conversations) {
            incoming.add(c.conversationId());
            SlackDmConversationEntity.SlackDmConversationKey key =
                    new SlackDmConversationEntity.SlackDmConversationKey(workspaceId, c.conversationId());
            SlackDmConversationEntity entity = repo.findById(key).orElseGet(SlackDmConversationEntity::new);
            entity.setId(key);
            entity.setGroup(c.isGroup());
            entity.setPeerUserIds(String.join(",", c.peerUserIds()));
            entity.setLatestTs(c.latestTs());
            entity.setLastSeenAt(c.lastSeenAt());
            repo.save(entity);
        }
        for (SlackDmConversationEntity existing : repo.findByIdWorkspaceId(workspaceId)) {
            if (!incoming.contains(existing.getId().getConversationId())) {
                repo.delete(existing);
            }
        }
    }

    private static SlackDmConversation toDomain(SlackDmConversationEntity entity)
    {
        return new SlackDmConversation(
                entity.getId().getWorkspaceId(),
                entity.getId().getConversationId(),
                entity.isGroup(),
                PEER_SPLITTER.splitToList(entity.getPeerUserIds() == null ? "" : entity.getPeerUserIds()),
                entity.getLatestTs(),
                entity.getLastSeenAt());
    }
}
