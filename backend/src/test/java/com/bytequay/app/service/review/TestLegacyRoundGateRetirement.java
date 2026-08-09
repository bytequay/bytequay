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
package com.bytequay.app.service.review;

import com.bytequay.app.repository.sqlite.RoundGateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestLegacyRoundGateRetirement
{
    private final RoundGateStore gates = mock(RoundGateStore.class);
    private final RoundGateSaga saga = new RoundGateSaga(gates, new ObjectMapper());

    @Test
    void everyLegacyMutationRejectsBeforeStoreCommandOrAdapterIo()
    {
        AtomicBoolean payloadMutated = new AtomicBoolean();
        assertRetired(() -> saga.approve("round-1"));
        assertRetired(() -> saga.drive("token-1"));
        assertRetired(() -> saga.editPayload(
                "task-1", "round-1", (Runnable) () -> payloadMutated.set(true)));
        assertRetired(() -> saga.prepareRecovery("task-1", 1));
        assertRetired(() -> saga.verifyRecoveryRequest("task-1"));
        assertRetired(() -> saga.resumeExternalSagaInCommand(null));
        assertRetired(() -> saga.onAuthorized(null));
        assertRetired(saga::reconcileActive);

        assertThat(payloadMutated).isFalse();
        verifyNoInteractions(gates);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
