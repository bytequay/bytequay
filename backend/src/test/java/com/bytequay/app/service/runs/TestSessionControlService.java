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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.ThreadRegistry;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSessionControlService
{
    @Test
    void allLegacyAgentRunControlsFailClosed()
    {
        AgentRunService runs = mock(AgentRunService.class);
        ThreadStore threads = mock(ThreadStore.class);
        ThreadTurnStore turns = mock(ThreadTurnStore.class);
        ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
        ThreadRegistry registry = mock(ThreadRegistry.class);
        AgentRun run = mock(AgentRun.class);
        when(runs.findById("run-legacy")).thenReturn(Optional.of(run));
        SessionControlService service = new SessionControlService(
                runs, threads, turns, scheduler, registry);

        assertRetired(() -> service.pause("run-legacy"));
        assertRetired(() -> service.stop("run-legacy"));
        assertRetired(() -> service.resume("run-legacy"));
        assertRetired(() -> service.restart("run-legacy"));

        verifyNoInteractions(threads, turns, scheduler, registry, run);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("retired")
                .hasMessageContaining("typed V2 owner control");
    }
}
