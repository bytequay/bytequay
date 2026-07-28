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
package com.bytequay.app.developmentflow.task;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;

import static java.util.Objects.requireNonNull;

/** TASK_TURN_RESULT consumer for ordinary V2 Task Brain conversation only. */
public final class TaskBrainConversationResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final AgentTurnOwnerResultCodec codec;
    private final TaskBrainConversationRuntime runtime;

    public TaskBrainConversationResultDeliveryPort(
            AgentTurnOwnerResultCodec codec,
            TaskBrainConversationRuntime runtime)
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
        return runtime.deliver(codec.decode(owner, expectedFence, rawResult));
    }
}
