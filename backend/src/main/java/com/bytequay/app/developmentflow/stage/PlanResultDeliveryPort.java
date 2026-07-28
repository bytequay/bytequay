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

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Exact callback boundary for Task provisioning and Plan-owned TaskTurns. */
public final class PlanResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final PlanRuntimeCoordinator runtime;

    public PlanResultDeliveryPort(PlanRuntimeCoordinator runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        return switch (owner.callbackRoute()) {
            case PlanRuntimeCoordinator.PROVISION_CALLBACK ->
                    runtime.deliverProvisioning(owner, expectedFence, rawResult);
            case PlanRuntimeCoordinator.TURN_CALLBACK ->
                    new DispatchTicket.DeliveryReceipt(
                            REJECTED,
                            "{\"schema\":\"PLAN_DELIVERY_V1\","
                                    + "\"result\":\"Plan TaskTurn delivery is not installed\"}");
            default -> new DispatchTicket.DeliveryReceipt(
                    REJECTED,
                    "{\"schema\":\"PLAN_DELIVERY_V1\","
                            + "\"result\":\"unknown callback route\"}");
        };
    }
}
