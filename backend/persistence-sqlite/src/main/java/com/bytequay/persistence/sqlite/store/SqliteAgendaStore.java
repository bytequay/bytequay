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

import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaRun;
import com.bytequay.core.store.AgendaStore;
import com.bytequay.persistence.sqlite.entity.AgendaDayEntity;
import com.bytequay.persistence.sqlite.entity.AgendaRunEntity;
import com.bytequay.persistence.sqlite.repository.AgendaDayJpaRepository;
import com.bytequay.persistence.sqlite.repository.AgendaRunJpaRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgendaStore
        implements AgendaStore
{
    private final AgendaDayJpaRepository days;
    private final AgendaRunJpaRepository runs;

    public SqliteAgendaStore(AgendaDayJpaRepository days, AgendaRunJpaRepository runs)
    {
        this.days = requireNonNull(days, "days is null");
        this.runs = requireNonNull(runs, "runs is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgendaDay> find(LocalDate day)
    {
        return days.findById(day.toString()).map(AgendaDayEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgendaDay> recent(int limit)
    {
        return days.findAllByOrderByIdDesc(Limit.of(limit)).stream().map(AgendaDayEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(AgendaDay day)
    {
        AgendaDayEntity entity = days.findById(day.day().toString()).orElseGet(() -> new AgendaDayEntity(day));
        entity.update(day);
        days.save(entity);
    }

    @Override
    @Transactional
    public void record(AgendaRun run)
    {
        runs.save(new AgendaRunEntity(run));
    }

    @Override
    @Transactional
    public void deleteBefore(LocalDate day)
    {
        days.deleteByIdLessThan(day.toString());
        runs.deleteByDayLessThan(day.toString());
    }
}
