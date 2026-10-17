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

import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.store.AssistantStore;
import com.bytequay.persistence.sqlite.entity.AssistantDayEntity;
import com.bytequay.persistence.sqlite.entity.AssistantItemEntity;
import com.bytequay.persistence.sqlite.repository.AssistantDayJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantItemJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAssistantStore
        implements AssistantStore
{
    private final AssistantDayJpaRepository days;
    private final AssistantItemJpaRepository items;

    public SqliteAssistantStore(AssistantDayJpaRepository days, AssistantItemJpaRepository items)
    {
        this.days = requireNonNull(days, "days is null");
        this.items = requireNonNull(items, "items is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantDay> find(LocalDate day)
    {
        return days.findById(day.toString()).map(AssistantDayEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(AssistantDay day)
    {
        AssistantDayEntity entity = days.findById(day.day().toString()).orElseGet(() -> new AssistantDayEntity(day));
        entity.update(day);
        days.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantItem> items(LocalDate day)
    {
        return items.findByDayOrderByOrdinalAsc(day.toString()).stream().map(AssistantItemEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void add(AssistantItem item)
    {
        // ponytail: the next ordinal is the day's count; the assistant writes one run at a time, and the unique ordinal refuses a second writer.
        items.save(new AssistantItemEntity(item, items.countByDay(item.day().toString())));
    }

    @Override
    @Transactional
    public void deleteBefore(LocalDate day)
    {
        days.deleteByIdLessThan(day.toString());
        items.deleteByDayLessThan(day.toString());
    }
}
