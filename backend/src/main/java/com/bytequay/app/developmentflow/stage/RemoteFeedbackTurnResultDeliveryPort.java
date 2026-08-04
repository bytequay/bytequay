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

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Route-specific dispatcher boundary for Remote feedback StageTurns. */
public final class RemoteFeedbackTurnResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final AgentTurnOwnerResultCodec codec;
    private final RemoteFeedbackRuntimeCoordinator runtime;

    public RemoteFeedbackTurnResultDeliveryPort(
            AgentTurnOwnerResultCodec codec,
            RemoteFeedbackRuntimeCoordinator runtime)
    {
        this.codec = requireNonNull(codec, "codec is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
            throws ExecutionPorts.ResultProtocolException
    {
        if (!RemoteFeedbackRuntimeCoordinator.TURN_CALLBACK.equals(
                owner.callbackRoute())) {
            return new DispatchTicket.DeliveryReceipt(
                    REJECTED, "Unknown Remote feedback StageTurn route");
        }
        try {
            return runtime.deliverStageTurn(
                    codec.decode(owner, expectedFence, rawResult));
        }
        catch (IllegalArgumentException failure) {
            // Classify it as a protocol failure so the dispatcher parks the
            // ticket. Without this the decode escapes as a plain exception and
            // the same undeliverable result is re-armed every retryDelay
            // forever — the spin the Local lane was fixed for.
            throw new ExecutionPorts.ResultProtocolException(
                    failure.getMessage(), failure);
        }
    }
}
