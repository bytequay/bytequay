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

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.developmentflow.compatibility.V2AgentRunProjection;
import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageDetailService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAgentRunApiRouting
{
    private final AgentRunService legacyRuns = mock(AgentRunService.class);
    private final StageDetailService legacyDetail = mock(StageDetailService.class);
    private final V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
    private final V2AgentRunProjection v2Runs = mock(V2AgentRunProjection.class);
    private final V2StageApiService v2Stages = mock(V2StageApiService.class);
    private final AgentRunController controller = controller();

    @Test
    void v2TaskRunAndLogReadsNeverReachLegacyRunOrStageStores()
    {
        AgentRun run = v2Run();
        StageDetailData detail = mock(StageDetailData.class);
        when(routes.isV2Task("task-v2")).thenReturn(true);
        when(v2Runs.listByTask("task-v2")).thenReturn(List.of(run));
        when(v2Runs.findById(run.id())).thenReturn(Optional.of(run));
        when(v2Stages.runDetail(run.id())).thenReturn(detail);

        assertThat(controller.runsForTask("task-v2")).containsExactly(run);
        assertThat(controller.run(run.id())).isSameAs(run);
        assertThat(controller.log(run.id())).isSameAs(detail);

        verify(v2Runs).listByTask("task-v2");
        verify(v2Stages).runDetail(run.id());
        verifyNoInteractions(legacyRuns, legacyDetail);
    }

    @Test
    void legacyTaskRunAndLogReadsKeepTheirExistingOwners()
    {
        UUID stageId = UUID.randomUUID();
        AgentRun run = legacyRun(stageId);
        StageDetailData detail = mock(StageDetailData.class);
        when(routes.isV2Task("legacy-task")).thenReturn(false);
        when(legacyRuns.findByTask("legacy-task", null, null))
                .thenReturn(List.of(run));
        when(legacyRuns.findById(run.id())).thenReturn(Optional.of(run));
        when(legacyDetail.getDetail(stageId)).thenReturn(detail);

        assertThat(controller.runsForTask("legacy-task")).containsExactly(run);
        assertThat(controller.run(run.id())).isSameAs(run);
        assertThat(controller.log(run.id())).isSameAs(detail);

        verify(legacyRuns).findByTask("legacy-task", null, null);
        verify(legacyDetail).getDetail(stageId);
        verifyNoInteractions(v2Runs, v2Stages);
    }

    private AgentRunController controller()
    {
        AgentRunController result = new AgentRunController(
                legacyRuns, legacyDetail, routes, v2Runs, v2Stages);
        return result;
    }

    private static AgentRun v2Run()
    {
        return new AgentRun(
                "v2-ticket:ticket-1", "task-v2", AgentRun.KIND_DEV,
                AgentRun.SOURCE_LOCAL, "stage-v2", null, "stage-v2",
                AgentRun.STATUS_RUNNING, 1, null, "Implement", null,
                Instant.EPOCH, null, "workspace-1", "trunk-1", "openai",
                null, 0, 0, 0, 1, "Implement", null, null);
    }

    private static AgentRun legacyRun(UUID stageId)
    {
        return new AgentRun(
                "legacy-run", "legacy-task", AgentRun.KIND_DEV,
                AgentRun.SOURCE_LOCAL, stageId.toString(), null,
                stageId.toString(), AgentRun.STATUS_SUCCEEDED, 1, null,
                "Done", null, Instant.EPOCH, Instant.EPOCH,
                "workspace-1", "legacy-trunk", "openai", null,
                0, 0, 0, 1, "Implement", null, "completed");
    }
}
