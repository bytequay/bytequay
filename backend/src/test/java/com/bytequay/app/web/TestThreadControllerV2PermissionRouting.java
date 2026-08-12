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
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.userwait.V2UserWaitService;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.repository.ThreadCheckpointStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.CheckpointTrigger;
import com.bytequay.app.service.threads.ConvIndexService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestThreadControllerV2PermissionRouting
{
    @Test
    void typedPermissionOwnedByAnotherTrunkNeverFallsThroughToLegacy()
    {
        ThreadService legacy = mock(ThreadService.class);
        V2UserWaitService typed = mock(V2UserWaitService.class);
        ThreadController controller = new ThreadController(
                legacy,
                mock(ConvIndexService.class),
                mock(ThreadCheckpointStore.class),
                mock(CheckpointTrigger.class),
                mock(WorkModelResolver.class),
                mock(ChatAttachmentStore.class),
                new ObjectMapper());
        controller.setV2Waits(typed);
        when(typed.findPermission("call-1"))
                .thenReturn(Optional.of(permission()));
        when(typed.findPermissionForTrunk("wrong-trunk", "call-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.decide(
                "wrong-trunk",
                new ThreadController.DecisionBody(
                        "call-1", PermissionDecision.ALLOW,
                        null, null, 0, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("does not belong");
        verifyNoInteractions(legacy);
    }

    private static V2UserWaitStore.PermissionRequest permission()
    {
        return new V2UserWaitStore.PermissionRequest(
                "permission-1", "call-1",
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "turn-1", "operation-1"),
                "shell", "shell", "{}", "digest", "{}", "OPEN",
                null, 0, Instant.EPOCH, null, null, null, null, null, 0,
                null, null, "WAITING", null, null);
    }
}
