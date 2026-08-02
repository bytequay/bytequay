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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentTurnOwnerResultCodec
{
    private static final DispatchTicket.OwnerReference OWNER =
            new DispatchTicket.OwnerReference(
                    DispatchTicket.OwnerKind.TASK_TURN,
                    "turn-1", "deliver-task-turn");
    private static final DispatchTicket.OperationFence FENCE =
            new DispatchTicket.OperationFence(
                    1L, null, null, "operation-1", 1,
                    null, null, null);

    @Test
    void synthesizesTypedEvidenceForANoLaunchCancellation()
    {
        AgentTurnOwnerResultCodec.OwnerResult result = codec().decode(
                OWNER, FENCE, DispatchTicket.DispatchResult.canceled(FENCE));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        assertThat(result.payload().turnId()).isEqualTo(OWNER.id());
        assertThat(result.payload().ownerKind()).isEqualTo(OWNER.kind());
        assertThat(result.payload().disposition()).isEqualTo(
                AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED);
        assertThat(result.payload().error())
                .isEqualTo("cancel requested before launch");
    }

    @Test
    void missingOrMalformedLaunchedPayloadRemainsInvalid()
    {
        DispatchTicket.DispatchResult missingFailed =
                new DispatchTicket.DispatchResult(
                        FENCE, DispatchTicket.Outcome.FAILED,
                        null, "{}", "failed");
        DispatchTicket.DispatchResult malformedCanceled =
                new DispatchTicket.DispatchResult(
                        FENCE, DispatchTicket.Outcome.CANCELED,
                        "{}", "{}", "canceled");

        assertThatThrownBy(() -> codec().decode(OWNER, FENCE, missingFailed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload is invalid");
        assertThatThrownBy(() -> codec().decode(OWNER, FENCE, malformedCanceled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload is invalid");
    }

    private static AgentTurnOwnerResultCodec codec()
    {
        return new AgentTurnOwnerResultCodec(new ObjectMapper());
    }
}
