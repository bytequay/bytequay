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

import com.bytequay.app.beans.workspace.TrunkDto;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTrunkControllerRuntimeProjection
{
    @Test
    void returnsProjectedV2RuntimeStateFromListAndDetail()
    {
        ThreadStore threads = mock(ThreadStore.class);
        V2TrunkRuntimeProjection runtime =
                mock(V2TrunkRuntimeProjection.class);
        Thread stored = thread(ThreadStatus.ERRORED, 0L, Instant.EPOCH);
        Thread projected = thread(
                ThreadStatus.RUNNING, 125L, Instant.ofEpochMilli(40));
        when(threads.listThreadsByWorkspace("workspace-1"))
                .thenReturn(List.of(stored));
        when(threads.findThreadById("trunk-1"))
                .thenReturn(Optional.of(stored));
        when(runtime.projectAll(List.of(stored)))
                .thenReturn(List.of(projected));
        when(runtime.project(stored)).thenReturn(projected);
        TrunkController controller = new TrunkController(threads, runtime);

        TrunkDto listed = controller.list("workspace-1").getFirst();
        TrunkDto detail = controller.get("workspace-1", "trunk-1");

        assertThat(listed.status()).isEqualTo("running");
        assertThat(listed.costUsdMilli()).isEqualTo(125L);
        assertThat(listed.updatedAt()).isEqualTo(40L);
        assertThat(detail).isEqualTo(listed);
    }

    private static Thread thread(
            ThreadStatus status, long costUsdMilli, Instant updatedAt)
    {
        return new Thread(
                "trunk-1", ThreadKind.CLI_AGENT, "codex", null, "Trunk",
                status, "model", costUsdMilli, 0, 0, Instant.EPOCH,
                updatedAt, null, null, ThreadFlow.BUILD, "workspace-1", null);
    }
}
