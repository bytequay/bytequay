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

import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.store.AssistantStore;
import com.bytequay.persistence.sqlite.entity.AssistantActionEntity;
import com.bytequay.persistence.sqlite.entity.AssistantConversationEntity;
import com.bytequay.persistence.sqlite.entity.AssistantItemEntity;
import com.bytequay.persistence.sqlite.repository.AssistantActionJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantConversationJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantItemJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAssistantStore
        implements AssistantStore
{
    private final AssistantConversationJpaRepository conversations;
    private final AssistantItemJpaRepository items;
    private final AssistantActionJpaRepository actions;

    public SqliteAssistantStore(AssistantConversationJpaRepository conversations, AssistantItemJpaRepository items, AssistantActionJpaRepository actions)
    {
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.items = requireNonNull(items, "items is null");
        this.actions = requireNonNull(actions, "actions is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantConversation> find(String conversationId)
    {
        return conversations.findById(conversationId).map(AssistantConversationEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(AssistantConversation conversation)
    {
        AssistantConversationEntity entity = conversations.findById(conversation.id()).orElseGet(() -> new AssistantConversationEntity(conversation));
        entity.update(conversation);
        conversations.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantConversation> conversations(int limit)
    {
        return conversations.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream().map(AssistantConversationEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantItem> items(String conversationId)
    {
        return items.findByConversationIdOrderByOrdinalAsc(conversationId).stream().map(AssistantItemEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void add(AssistantItem item)
    {
        // ponytail: the next ordinal is the conversation's count; the assistant writes one item at a time, and the unique ordinal refuses a second writer.
        items.save(new AssistantItemEntity(item, items.countByConversationId(item.conversationId())));
    }

    @Override
    @Transactional
    public void addAction(AssistantAction action)
    {
        actions.save(new AssistantActionEntity(action));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantAction> actions(String itemId)
    {
        return actions.findByItemId(itemId).stream().map(AssistantActionEntity::toDomain).sorted(comparing(AssistantAction::at)).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void deleteBefore(Instant updatedBefore, LocalDate actionsBefore)
    {
        List<AssistantConversationEntity> old = conversations.findByUpdatedAtBefore(updatedBefore);
        items.deleteByConversationIdIn(old.stream().map(AssistantConversationEntity::getId).collect(toImmutableList()));
        conversations.deleteAll(old);
        actions.deleteByDayLessThan(actionsBefore.toString());
    }
}
