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
package com.bytequay.agent.core.store;

import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.TaskId;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the steer ledger.
 *
 * <p>A turn started directly is its own delivery record; only a steer, which adds to a turn
 * without creating one, needs a row of its own.
 */
public interface SteerDeliveryStore
{
    /**
     * Inserts an accepted steer.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the task already holds its idempotency key
     */
    void insert(AgentInputDelivery delivery);

    Optional<AgentInputDelivery> findSteerByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /**
     * Replaces a delivery using optimistic version checking.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the version changed
     */
    AgentInputDelivery update(AgentInputDelivery delivery);

    /** Every steer that was accepted or being sent when this process last stopped. */
    List<AgentInputDelivery> listUnsettled();
}
