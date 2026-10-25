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
package com.bytequay.app.assistant;

import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.store.AssistantStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** The assistant's store held in memory, for tests that read back what a run wrote. */
final class MemoryAssistantStore
        implements AssistantStore
{
    final Map<String, AssistantConversation> conversations = new HashMap<>();
    private final List<AssistantItem> items = new ArrayList<>();
    private final List<AssistantAction> actions = new ArrayList<>();

    @Override
    public synchronized Optional<AssistantConversation> find(String conversationId)
    {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public synchronized void save(AssistantConversation conversation)
    {
        conversations.put(conversation.id(), conversation);
    }

    @Override
    public synchronized List<AssistantConversation> conversations(int limit)
    {
        return conversations.values().stream().sorted(Comparator.comparing(AssistantConversation::updatedAt).reversed()).limit(limit).collect(toImmutableList());
    }

    @Override
    public synchronized List<AssistantItem> items(String conversationId)
    {
        return items.stream().filter(item -> item.conversationId().equals(conversationId)).collect(toImmutableList());
    }

    @Override
    public synchronized void add(AssistantItem item)
    {
        items.add(item);
    }

    @Override
    public synchronized void addAction(AssistantAction action)
    {
        actions.add(action);
    }

    @Override
    public synchronized List<AssistantAction> actions(String itemId)
    {
        return actions.stream().filter(action -> action.itemId().equals(itemId)).collect(toImmutableList());
    }

    @Override
    public synchronized void deleteBefore(Instant updatedBefore, LocalDate actionsBefore)
    {
        conversations.values().removeIf(conversation -> conversation.updatedAt().isBefore(updatedBefore));
        items.removeIf(item -> !conversations.containsKey(item.conversationId()));
        actions.removeIf(action -> action.day().isBefore(actionsBefore));
    }
}
