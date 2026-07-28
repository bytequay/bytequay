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

/** Route-specific dispatcher boundary for Remote feedback validation. */
public final class RemoteFeedbackValidationResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final RemoteFeedbackRuntimeCoordinator runtime;

    public RemoteFeedbackValidationResultDeliveryPort(
            RemoteFeedbackRuntimeCoordinator runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        return runtime.deliverValidation(owner, expectedFence, rawResult);
    }
}
