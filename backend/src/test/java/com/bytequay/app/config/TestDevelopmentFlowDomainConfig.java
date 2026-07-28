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
package com.bytequay.app.config;

import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.PlanToLocalHandoff;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteTerminalToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.ReplanHandoff;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.BrainVerdictHandoff;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TestDevelopmentFlowDomainConfig
{
    @Autowired
    private ApplicationContext context;

    @Test
    void productionGraphUsesOneConcreteOwnerAndStoreInstance()
    {
        assertThat(beans(TrunkManager.Store.class)).hasSize(1);
        assertThat(beans(TaskManager.Store.class)).hasSize(1);
        Map<String, StageManager.Store> stageStores = beans(StageManager.Store.class);
        assertThat(stageStores).hasSize(1);

        Object stageStore = stageStores.values().iterator().next();
        assertThat(only(PlanStageManager.ApprovalStore.class)).isSameAs(stageStore);
        assertThat(only(PlanStageManager.RevisionStore.class)).isSameAs(stageStore);
        assertThat(only(LocalDevelopmentStageManager.EvidenceStore.class))
                .isSameAs(stageStore);
        assertThat(beans(RemoteDevelopmentStageManager.EvidenceStore.class)).hasSize(1);

        assertThat(beans(TrunkManager.class)).hasSize(1);
        assertThat(beans(TaskManager.class)).hasSize(1);
        assertThat(beans(PlanStageManager.class)).hasSize(1);
        assertThat(beans(LocalDevelopmentStageManager.class)).hasSize(1);
        assertThat(beans(RemoteDevelopmentStageManager.class)).hasSize(1);
        assertThat(beans(CleanupStageManager.class)).hasSize(1);
        assertThat(beans(StageManager.class)).hasSize(4);
    }

    @Test
    void productionGraphComposesEverySynchronousHandoffButNotV2Dispatch()
    {
        assertThat(beans(BrainVerdictHandoff.class)).hasSize(1);
        assertThat(beans(TaskControlHandoff.class)).hasSize(1);
        assertThat(beans(ProvisionToPlanHandoff.class)).hasSize(1);
        assertThat(beans(PlanToLocalHandoff.class)).hasSize(1);
        assertThat(beans(LocalToRemoteHandoff.class)).hasSize(1);
        assertThat(beans(RemoteTerminalToCleanupHandoff.class)).hasSize(1);
        assertThat(beans(CleanupCompletionHandoff.class)).hasSize(1);
        assertThat(beans(CleanupQuiescenceHandoff.class)).hasSize(1);
        assertThat(beans(ReplanHandoff.class)).hasSize(3);
        assertThat(beans(CancellationToCleanupHandoff.class)).hasSize(3);
        assertThat(beans(ExecutionDispatcher.class)).isEmpty();
    }

    private <T> Map<String, T> beans(Class<T> type)
    {
        return context.getBeansOfType(type);
    }

    private <T> T only(Class<T> type)
    {
        return beans(type).values().iterator().next();
    }
}
