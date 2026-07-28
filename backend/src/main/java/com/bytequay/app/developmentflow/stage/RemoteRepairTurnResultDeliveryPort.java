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
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;

import java.util.Set;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Route-specific dispatcher boundary for CI and branch repair typed Turns. */
public final class RemoteRepairTurnResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private static final Set<String> ROUTES = Set.of(
            RemoteRepairTurnRuntime.CI_STAGE_CALLBACK,
            RemoteRepairTurnRuntime.CI_BRAIN_CALLBACK,
            RemoteRepairTurnRuntime.BRANCH_STAGE_CALLBACK,
            RemoteRepairTurnRuntime.BRANCH_BRAIN_CALLBACK,
            RemoteRepairTurnRuntime.STEERING_CALLBACK);

    private final AgentTurnOwnerResultCodec codec;
    private final RemoteRepairTurnRuntime runtime;

    public RemoteRepairTurnResultDeliveryPort(
            AgentTurnOwnerResultCodec codec,
            RemoteRepairTurnRuntime runtime)
    {
        this.codec = requireNonNull(codec, "codec is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        if (!ROUTES.contains(owner.callbackRoute())) {
            return new DispatchTicket.DeliveryReceipt(
                    REJECTED, "Unknown Remote repair Turn route");
        }
        return runtime.deliver(codec.decode(owner, expectedFence, rawResult));
    }
}
