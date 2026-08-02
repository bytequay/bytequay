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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;

import static java.util.Objects.requireNonNull;

/** Callback adapter for the two finite Local publish base-sync effects. */
public final class LocalPublishBaseSyncResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final LocalPublishBaseSyncRuntimeCoordinator coordinator;

    public LocalPublishBaseSyncResultDeliveryPort(
            LocalPublishBaseSyncRuntimeCoordinator coordinator)
    {
        this.coordinator = requireNonNull(coordinator, "coordinator is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        return coordinator.deliver(owner, expectedFence, rawResult);
    }
}
