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

import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.persistence.sqlite.entity.GoalExchangeEntity;
import com.bytequay.persistence.sqlite.repository.GoalExchangeJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalExchangeStore
        implements GoalExchangeStore
{
    private final GoalExchangeJpaRepository exchanges;

    public SqliteGoalExchangeStore(GoalExchangeJpaRepository exchanges)
    {
        this.exchanges = requireNonNull(exchanges, "exchanges is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoalExchange> find(String exchangeId)
    {
        return exchanges.findById(exchangeId).map(GoalExchangeEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalExchange> list(String goalId)
    {
        return exchanges.findByGoalIdOrderByAskedAtAscIdAsc(goalId).stream().map(GoalExchangeEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalExchange> pending()
    {
        return exchanges.findByState(GoalExchange.State.PENDING.name()).stream().map(GoalExchangeEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(GoalExchange exchange)
    {
        // Updates the loaded row; only an exchange with no row yet is persisted as new.
        GoalExchangeEntity entity = exchanges.findById(exchange.id()).orElseGet(() -> new GoalExchangeEntity(exchange));
        entity.update(exchange);
        exchanges.saveAndFlush(entity);
    }
}
