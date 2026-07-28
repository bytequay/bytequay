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
package com.bytequay.app.developmentflow.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static org.assertj.core.api.Assertions.assertThat;

class TestResultDeliveryRouter
{
    @Test
    void forwardsPostCommitAndRunsSharedRecoveryPortOnce()
            throws Exception
    {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger recovered = new AtomicInteger();
        ExecutionPorts.ResultDeliveryPort port = new ExecutionPorts.ResultDeliveryPort()
        {
            @Override
            public DispatchTicket.DeliveryReceipt deliver(
                    DispatchTicket.OwnerReference owner,
                    DispatchTicket.OperationFence expectedFence,
                    DispatchTicket.DispatchResult rawResult)
            {
                return new DispatchTicket.DeliveryReceipt(ACCEPTED, "accepted");
            }

            @Override
            public void afterDeliveryCommitted(
                    DispatchTicket.OwnerReference owner,
                    DispatchTicket.OperationFence expectedFence,
                    DispatchTicket.DispatchResult rawResult,
                    DispatchTicket.DeliveryReceipt receipt)
            {
                committed.incrementAndGet();
            }

            @Override
            public void recoverCommittedDeliveries(int limit)
            {
                recovered.incrementAndGet();
            }
        };
        ResultDeliveryRouter router = new ResultDeliveryRouter(Map.of(
                "PRIMARY", port,
                "ALIAS", port));
        DispatchTicket.OwnerReference owner =
                new DispatchTicket.OwnerReference(STAGE, "stage-1", "PRIMARY");
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                null, null, null);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, SUCCEEDED, "evidence", "{}", null);
        DispatchTicket.DeliveryReceipt receipt = router.deliver(owner, fence, result);

        router.afterDeliveryCommitted(owner, fence, result, receipt);
        router.recoverCommittedDeliveries(10);

        assertThat(committed).hasValue(1);
        assertThat(recovered).hasValue(1);
    }
}
