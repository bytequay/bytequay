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

import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.persistence.sqlite.entity.AgentInputDeliveryEntity;
import com.bytequay.persistence.sqlite.repository.AgentInputDeliveryJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.persistence.sqlite.store.StoreWrites.AGENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSteerDeliveryStore
        implements SteerDeliveryStore
{
    private static final List<AgentInputDelivery.State> UNSETTLED = ImmutableList.of(AgentInputDelivery.State.ACCEPTED, AgentInputDelivery.State.SENDING);

    private final AgentInputDeliveryJpaRepository repository;

    public SqliteSteerDeliveryStore(AgentInputDeliveryJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentInputDelivery delivery)
    {
        requireNonNull(delivery, "delivery is null");
        StoreWrites.insert(repository, delivery.id().toString(), "Steer delivery", () -> AgentInputDeliveryEntity.fromDomain(delivery), AGENT);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentInputDelivery> findSteerByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkText(idempotencyKey, "idempotencyKey");
        return repository.findByTaskIdAndIdempotencyKey(taskId.toString(), idempotencyKey)
                .map(AgentInputDeliveryEntity::toDomain);
    }

    @Override
    @Transactional
    public AgentInputDelivery update(AgentInputDelivery delivery)
    {
        requireNonNull(delivery, "delivery is null");
        return StoreWrites.update(
                repository, delivery.id().toString(), "Steer delivery", delivery.version(), AgentInputDeliveryEntity::getVersion, entity -> entity.updateFrom(delivery),
                () -> new AgentStorageException("Steer delivery is absent: " + delivery.id()), AGENT)
                .toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentInputDelivery> listUnsettled()
    {
        return repository.findByStateInOrderByCreatedAtAsc(UNSETTLED).stream()
                .map(AgentInputDeliveryEntity::toDomain)
                .collect(toImmutableList());
    }
}
