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

import static java.util.Objects.requireNonNull;

/** Narrow TaskTurn consumer for Remote feedback Brain results only. */
public final class RemoteFeedbackBrainResultDeliveryPort
        implements AgentTurnOwnerResultCodec.OwnerDeliveryPort
{
    private final RemoteFeedbackRuntimeCoordinator runtime;

    public RemoteFeedbackBrainResultDeliveryPort(
            RemoteFeedbackRuntimeCoordinator runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            AgentTurnOwnerResultCodec.OwnerResult result)
            throws ExecutionPorts.ResultProtocolException
    {
        try {
            return runtime.deliverBrain(result);
        }
        catch (IllegalArgumentException | IllegalStateException failure) {
            // Park rather than spin: the verdict is frozen on the ticket, so a
            // re-delivery reproduces this failure exactly.
            throw new ExecutionPorts.ResultProtocolException(
                    failure.getMessage(), failure);
        }
    }
}
