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
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.store.AssistantStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** The assistant's store held in memory, for tests that read back what a run wrote. */
final class MemoryAssistantStore
        implements AssistantStore
{
    final Map<LocalDate, AssistantDay> days = new HashMap<>();
    private final List<AssistantItem> items = new ArrayList<>();
    private final List<AssistantAction> actions = new ArrayList<>();

    @Override
    public synchronized Optional<AssistantDay> find(LocalDate day)
    {
        return Optional.ofNullable(days.get(day));
    }

    @Override
    public synchronized void save(AssistantDay day)
    {
        days.put(day.day(), day);
    }

    @Override
    public synchronized List<AssistantItem> items(LocalDate day)
    {
        return items.stream().filter(item -> item.day().equals(day)).collect(toImmutableList());
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
    public synchronized void deleteBefore(LocalDate day)
    {
        days.keySet().removeIf(value -> value.isBefore(day));
        items.removeIf(item -> item.day().isBefore(day));
        actions.removeIf(action -> action.day().isBefore(day));
    }
}
