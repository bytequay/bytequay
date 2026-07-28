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

import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.PlanToLocalHandoff;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.RemoteCiFailureClassifier;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeObservationCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationDomainHooks;
import com.bytequay.app.developmentflow.stage.RemoteObservationMaintainer;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.RemoteTerminalObservationCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteTerminalToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.ReplanHandoff;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.task.BrainVerdictHandoff;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.creation.TaskCreationHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.ReviewBuildOutcomeService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

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
    public ThreadTurnHandoff threadTurnHandoff(
            TrunkManager trunks,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        return new ThreadTurnHandoff(
                trunks, json, Clock.systemUTC(), serverPort);
    }

    @Bean
    public TaskManager taskManager(
            TaskCommandExecutor commands, TaskManager.Store store)
    {
        return new TaskManager(commands, store);
    }

    /** Command-side bean only; no production controller routes Task creation here yet. */
    @Bean
    public TaskCreationHandoff taskCreationHandoff(
            TaskCommandExecutor commands,
            TrunkManager trunks,
            TaskManager tasks,
            IdGenerator ids)
    {
        return new TaskCreationHandoff(commands, trunks, tasks, ids);
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
    public PlanRuntimeCoordinator planRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            PlanStageManager plan,
            SqlitePlanRuntimeStore store,
            PlanToLocalHandoff planToLocal,
            LocalDevelopmentRuntimeCoordinator localRuntime,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        return new PlanRuntimeCoordinator(
                commands, tasks, plan, store, planToLocal, localRuntime, json,
                Clock.systemUTC(), serverPort);
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
    public LocalDevelopmentRuntimeCoordinator localDevelopmentRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        return new LocalDevelopmentRuntimeCoordinator(
                commands, tasks, local, store, fingerprints, git, json,
                Clock.systemUTC(), serverPort);
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
    public RemoteDevelopmentRuntimeCoordinator remoteDevelopmentRuntimeCoordinator(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore store,
            ObjectMapper json)
    {
        return new RemoteDevelopmentRuntimeCoordinator(
                commands, remote, store, json, Clock.systemUTC());
    }

    @Bean
    public RemoteFeedbackRuntimeCoordinator remoteFeedbackRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore remoteStore,
            SqliteRemoteFeedbackLoopStore feedbackStore,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort)
    {
        return new RemoteFeedbackRuntimeCoordinator(
                commands, tasks, remote, remoteStore, feedbackStore,
                fingerprints, git, json, Clock.systemUTC(), serverPort);
    }

    @Bean
    public RemoteRepairTurnRuntime remoteRepairTurnRuntime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteRemoteRuntimeStore remoteStore,
            SqliteRemoteRepairTurnStore turns,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            @Value("${server.port:53123}") int serverPort,
            @Value("${bytequay.development-flow.remote-ci.require-brain-review:true}")
            boolean requireCiBrainReview)
    {
        return new RemoteRepairTurnRuntime(
                commands, tasks, remoteStore, turns, fingerprints, git, json,
                Clock.systemUTC(), serverPort, requireCiBrainReview);
    }

    @Bean
    public RemoteCiRepairRuntimeCoordinator remoteCiRepairRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            RemoteCiFailureClassifier classifier,
            RemoteRepairTurnRuntime repairs,
            ObjectMapper json,
            @Value("${bytequay.development-flow.remote-ci.rerun-limit:1}")
            int rerunLimit,
            @Value("${bytequay.development-flow.remote-ci.fix-attempt-limit:3}")
            int fixAttemptLimit,
            @Value("${bytequay.development-flow.remote-ci.delivery-retry-limit:3}")
            int deliveryRetryLimit,
            @Value("${bytequay.development-flow.remote-ci.push-limit:3}")
            int pushLimit)
    {
        return new RemoteCiRepairRuntimeCoordinator(
                commands, store, classifier,
                new SqliteRemoteRuntimeStore.CiBudgets(
                        rerunLimit, fixAttemptLimit, deliveryRetryLimit, pushLimit),
                repairs, json, Clock.systemUTC());
    }

    @Bean
    public BranchSyncRuntimeCoordinator branchSyncRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            RemoteRepairTurnRuntime repairs,
            ObjectMapper json,
            @Value("${bytequay.development-flow.branch-sync.require-brain-review:true}")
            boolean requireBrainReview)
    {
        return new BranchSyncRuntimeCoordinator(
                commands, store, repairs, repairs, requireBrainReview, json,
                Clock.systemUTC());
    }

    @Bean
    public RemoteMergeObservationCoordinator remoteMergeObservationCoordinator(
            SqliteRemoteMergeRuntimeStore runtime,
            SqliteMergeOperationStore operations,
            RemoteTerminalObservationCoordinator terminal)
    {
        return new RemoteMergeObservationCoordinator(
                runtime, operations, terminal, Clock.systemUTC());
    }

    @Bean
    public RemoteObservationDomainHooks remoteObservationDomainHooks(
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteDevelopmentStageManager remote,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            BranchSyncRuntimeCoordinator branchSync,
            RemoteMergeObservationCoordinator mergeObservation,
            RemoteMergeRuntimeCoordinator merges)
    {
        return new RemoteObservationDomainHooks(
                store, remote, ciRepair, branchSync, mergeObservation, merges);
    }

    @Bean
    public RemoteDevelopmentObservationConsumer remoteObservationConsumer(
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteFeedbackRuntimeCoordinator feedback,
            RemoteDevelopmentRuntimeCoordinator remote,
            RemoteObservationDomainHooks hooks)
    {
        return new RemoteDevelopmentObservationConsumer(
                store, feedback, remote,
                new RemoteDevelopmentObservationConsumer.Hooks(
                        hooks::acceptCiInCommand,
                        hooks::acceptBranchInCommand,
                        hooks::acceptMergeInCommand,
                        hooks::acceptReadinessInCommand),
                Clock.systemUTC());
    }

    @Bean
    public RemoteObservationRuntimeCoordinator remoteObservationRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            RemoteDevelopmentObservationConsumer consumer,
            ObjectMapper json)
    {
        return new RemoteObservationRuntimeCoordinator(
                commands, store, consumer, json, Clock.systemUTC());
    }

    @Bean
    public RemoteObservationMaintainer remoteObservationMaintainer(
            SqliteRemoteRuntimeStore store,
            RemoteObservationRuntimeCoordinator observations,
            @Value("${bytequay.development-flow.remote-observation.interval-ms:20000}")
            long intervalMs,
            @Value("${bytequay.development-flow.remote-observation.batch-size:100}")
            int batchSize)
    {
        return new RemoteObservationMaintainer(
                store, observations, Duration.ofMillis(intervalMs), batchSize);
    }

    @Bean
    public CleanupStageManager cleanupStageManager(
            TaskCommandExecutor commands, StageManager.Store store)
    {
        return new CleanupStageManager(commands, store);
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
            TaskManager tasks,
            ReviewBuildOutcomeService reviewOutcomes)
    {
        return new CleanupCompletionHandoff(
                commands, cleanup, tasks, reviewOutcomes);
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
            PlanStageManager plan,
            PlanRuntimeCoordinator runtime)
    {
        return new ReplanHandoff(
                commands, source, tasks, plan, runtime::startReplanDraftInCommand);
    }

    @Bean
    public ReplanHandoff localDevelopmentReplanHandoff(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager source,
            TaskManager tasks,
            PlanStageManager plan,
            PlanRuntimeCoordinator runtime)
    {
        return new ReplanHandoff(
                commands, source, tasks, plan, runtime::startReplanDraftInCommand);
    }

    @Bean
    public ReplanHandoff remoteDevelopmentReplanHandoff(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager source,
            TaskManager tasks,
            PlanStageManager plan,
            PlanRuntimeCoordinator runtime)
    {
        return new ReplanHandoff(
                commands, source, tasks, plan, runtime::startReplanDraftInCommand);
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
