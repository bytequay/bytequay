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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestV2UserWaitResultDeliveryPort
{
    @Test
    void delegatesNoLaunchCancellationWithoutParsingATypedPayload()
            throws Exception
    {
        V2UserWaitStore waits = mock(V2UserWaitStore.class);
        ExecutionPorts.ResultDeliveryPort delegate =
                mock(ExecutionPorts.ResultDeliveryPort.class);
        V2UserWaitResultDeliveryPort delivery = new V2UserWaitResultDeliveryPort(
                waits, delegate, new ObjectMapper(), Clock.systemUTC());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK_TURN,
                "turn-1", "deliver-task-turn");
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, "operation-1", 1,
                null, null, null);
        DispatchTicket.DispatchResult canceled =
                DispatchTicket.DispatchResult.canceled(fence);
        DispatchTicket.DeliveryReceipt receipt = new DispatchTicket.DeliveryReceipt(
                DispatchTicket.Acceptance.ACCEPTED, "{}");
        when(delegate.deliver(owner, fence, canceled)).thenReturn(receipt);

        assertThat(delivery.deliver(owner, fence, canceled)).isSameAs(receipt);
        delivery.afterDeliveryCommitted(owner, fence, canceled, receipt);

        verify(delegate).deliver(owner, fence, canceled);
        verify(delegate).afterDeliveryCommitted(owner, fence, canceled, receipt);
        verifyNoInteractions(waits);
    }
}
