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
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production command-side graph for the V2 domain owners and their atomic
 * handoffs. Task creation and asynchronous dispatch remain disabled at their
 * routing boundaries while this graph is introduced alongside the legacy flow.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentFlowDomainConfig
{
    @Bean
    public TrunkManager trunkManager(
            TaskCommandExecutor commands, TrunkManager.Store store)
    {
        return new TrunkManager(commands, store);
    }

    @Bean
    public TaskManager taskManager(
            TaskCommandExecutor commands, TaskManager.Store store)
    {
        return new TaskManager(commands, store);
    }

    @Bean
    public PlanStageManager planStageManager(
            TaskCommandExecutor commands,
            StageManager.Store store,
            PlanStageManager.ApprovalStore approvals,
            PlanStageManager.RevisionStore revisions)
    {
        return new PlanStageManager(commands, store, approvals, revisions);
    }

    @Bean
    public LocalDevelopmentStageManager localDevelopmentStageManager(
            TaskCommandExecutor commands,
            StageManager.Store store,
            LocalDevelopmentStageManager.EvidenceStore evidence)
    {
        return new LocalDevelopmentStageManager(commands, store, evidence);
    }

    @Bean
    public RemoteDevelopmentStageManager remoteDevelopmentStageManager(
            TaskCommandExecutor commands,
            StageManager.Store store,
            RemoteDevelopmentStageManager.EvidenceStore evidence)
    {
        return new RemoteDevelopmentStageManager(commands, store, evidence);
    }

    @Bean
    public CleanupStageManager cleanupStageManager(
            TaskCommandExecutor commands, StageManager.Store store)
    {
        return new CleanupStageManager(commands, store);
    }

    /** Remote protocol evidence is not mapped yet, so every proof fails closed. */
    @Bean
    @ConditionalOnMissingBean(RemoteDevelopmentStageManager.EvidenceStore.class)
    public RemoteDevelopmentStageManager.EvidenceStore remoteEvidenceStore()
    {
        return RemoteDevelopmentStageManager.EvidenceStore.empty();
    }

    @Bean
    public BrainVerdictHandoff brainVerdictHandoff(
            TaskCommandExecutor commands,
            TaskManager tasks,
            PlanStageManager plan,
            LocalDevelopmentStageManager local)
    {
        return new BrainVerdictHandoff(commands, tasks, plan, local);
    }

    @Bean
    public TaskControlHandoff taskControlHandoff(
            TaskCommandExecutor commands, TaskManager tasks)
    {
        return new TaskControlHandoff(commands, tasks);
    }

    @Bean
    public ProvisionToPlanHandoff provisionToPlanHandoff(
            TaskCommandExecutor commands, TaskManager tasks, PlanStageManager plan)
    {
        return new ProvisionToPlanHandoff(commands, tasks, plan);
    }

    @Bean
    public PlanToLocalHandoff planToLocalHandoff(
            TaskCommandExecutor commands,
            PlanStageManager plan,
            TaskManager tasks,
            LocalDevelopmentStageManager local)
    {
        return new PlanToLocalHandoff(commands, plan, tasks, local);
    }

    @Bean
    public LocalToRemoteHandoff localToRemoteHandoff(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            TaskManager tasks,
            RemoteDevelopmentStageManager remote)
    {
        return new LocalToRemoteHandoff(commands, local, tasks, remote);
    }

    @Bean
    public RemoteTerminalToCleanupHandoff remoteTerminalToCleanupHandoff(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        return new RemoteTerminalToCleanupHandoff(commands, remote, tasks, cleanup);
    }

    @Bean
    public CleanupCompletionHandoff cleanupCompletionHandoff(
            TaskCommandExecutor commands,
            CleanupStageManager cleanup,
            TaskManager tasks)
    {
        return new CleanupCompletionHandoff(commands, cleanup, tasks);
    }

    @Bean
    public CleanupQuiescenceHandoff cleanupQuiescenceHandoff(
            TaskCommandExecutor commands,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        return new CleanupQuiescenceHandoff(commands, tasks, cleanup);
    }

    @Bean
    public ReplanHandoff planReplanHandoff(
            TaskCommandExecutor commands,
            PlanStageManager source,
            TaskManager tasks,
            PlanStageManager plan)
    {
        return new ReplanHandoff(commands, source, tasks, plan);
    }

    @Bean
    public ReplanHandoff localDevelopmentReplanHandoff(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager source,
            TaskManager tasks,
            PlanStageManager plan)
    {
        return new ReplanHandoff(commands, source, tasks, plan);
    }

    @Bean
    public ReplanHandoff remoteDevelopmentReplanHandoff(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager source,
            TaskManager tasks,
            PlanStageManager plan)
    {
        return new ReplanHandoff(commands, source, tasks, plan);
    }

    @Bean
    public CancellationToCleanupHandoff planCancellationToCleanupHandoff(
            TaskCommandExecutor commands,
            PlanStageManager source,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        return new CancellationToCleanupHandoff(commands, source, tasks, cleanup);
    }

    @Bean
    public CancellationToCleanupHandoff localDevelopmentCancellationToCleanupHandoff(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager source,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        return new CancellationToCleanupHandoff(commands, source, tasks, cleanup);
    }

    @Bean
    public CancellationToCleanupHandoff remoteDevelopmentCancellationToCleanupHandoff(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager source,
            TaskManager tasks,
            CleanupStageManager cleanup)
    {
        return new CancellationToCleanupHandoff(commands, source, tasks, cleanup);
    }
}
