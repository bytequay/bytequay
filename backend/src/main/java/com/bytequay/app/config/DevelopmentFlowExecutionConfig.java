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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
import com.bytequay.app.developmentflow.execution.LegacyCapacityLeaseMaintainer;
import com.bytequay.app.developmentflow.execution.ResultDeliveryRouter;
import com.bytequay.app.developmentflow.execution.TaskTurnResultDeliveryRouter;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ApiAgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.CliAgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.CredentialApiProviderResolver;
import com.bytequay.app.developmentflow.execution.agentturn.LoopbackOwnerMcpClient;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.RoutingAgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationResultDelivery;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupEffects;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.execution.merge.GitHubMergeEffects;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler;
import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.execution.provisioning.SqliteProvisionTaskOperationStore;
import com.bytequay.app.developmentflow.execution.publish.GitHubPublishEffects;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.SqlitePublishOperationStore;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteEffects;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteObserver;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.RemoteMarkReadyOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteFeedbackEffectOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteMarkReadyOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteAgentTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteReviewAssignmentTurnStore;
import com.bytequay.app.developmentflow.persistence.SqliteThreadTurnOperationStore;
import com.bytequay.app.developmentflow.stage.BranchSyncResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.LocalBrainResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.LocalValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.LocalValidationResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.MergeResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PlanResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteCiEffectResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiRerunResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackBrainResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackEffectResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackValidationResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteMarkReadyResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePublishResultStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.developmentflow.trunk.PlanningBaseRefreshOperationHandler;
import com.bytequay.app.developmentflow.trunk.PlanningBaseTurnRuntime;
import com.bytequay.app.developmentflow.trunk.SqlitePlanningBaseTurnStore;
import com.bytequay.app.developmentflow.trunk.SqliteTaskOutcomeSummaryStore;
import com.bytequay.app.developmentflow.trunk.TaskOutcomeSummaryResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TaskOutcomeSummaryRuntime;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.ThreadTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadSettingsStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.ReviewAssignmentTurnContinuation;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLEANUP;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.MERGE;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REMOTE_OBSERVATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REVIEW;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static java.util.Objects.requireNonNull;

/**
 * Shared capacity wiring is always active during LEGACY/V2 coexistence.
 * V2 dispatch requires explicit Remote effect gateways and fails closed when
 * either external adapter is absent.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentFlowExecutionConfig
{
    @Bean
    @Primary
    @ConditionalOnMissingBean(ExecutionPorts.ResultDeliveryPort.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ExecutionPorts.ResultDeliveryPort v2ResultDelivery(
            PlanningBaseTurnRuntime planningBase,
            PlanRuntimeCoordinator planRuntime,
            LocalDevelopmentRuntimeCoordinator localRuntime,
            RemoteFeedbackRuntimeCoordinator remoteFeedbackRuntime,
            RemoteDevelopmentRuntimeCoordinator remoteRuntime,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager localManager,
            LocalToRemoteHandoff localToRemote,
            RemoteObservationRuntimeCoordinator remoteObservations,
            PRService prs,
            TaskStore tasks,
            SqlitePublishResultStore publishStore,
            SqliteCleanupOperationStore cleanupStore,
            CleanupCompletionHandoff cleanupCompletion,
            TrunkManager trunks,
            RemoteCiRepairRuntimeCoordinator remoteCi,
            BranchSyncRuntimeCoordinator branchSync,
            RemoteRepairTurnRuntime remoteRepairTurns,
            RemoteDevelopmentStageManager remoteManager,
            SqliteMergeOperationStore mergeOperations,
            SqliteReviewAssignmentTurnStore reviewAssignmentTurns,
            SqliteTaskOutcomeSummaryStore outcomeSummaries,
            TaskOutcomeSummaryRuntime outcomeSummaryRuntime,
            ObjectProvider<ReviewAssignmentTurnContinuation> reviewContinuation,
            JdbcTemplate jdbc,
            ObjectMapper json)
    {
        PlanResultDeliveryPort plan = new PlanResultDeliveryPort(planRuntime);
        AgentTurnOwnerResultCodec codec = new AgentTurnOwnerResultCodec(json);
        LocalDevelopmentResultDeliveryPort localDelivery =
                new LocalDevelopmentResultDeliveryPort(codec, localRuntime);
        LocalBrainResultDeliveryPort brain =
                new LocalBrainResultDeliveryPort(localRuntime);
        ExecutionPorts.ResultDeliveryPort brainDelivery =
                (owner, fence, result) -> brain.deliver(
                        codec.decode(owner, fence, result));
        ExecutionPorts.ResultDeliveryPort taskTurns =
                new TaskTurnResultDeliveryRouter(jdbc, Map.of(
                        "PLAN_DRAFT", plan,
                        "PLAN_SELF_REVIEW", plan,
                        "DEVELOPMENT_BRAIN_REVIEW", brainDelivery,
                        "TASK_COMPLETION_SUMMARY",
                        new TaskOutcomeSummaryResultDeliveryPort(
                                outcomeSummaries, outcomeSummaryRuntime,
                                codec, json, Clock.systemUTC())));
        LocalValidationResultDeliveryPort validation =
                new LocalValidationResultDeliveryPort(localRuntime);
        PublishResultDeliveryPort publish = new PublishResultDeliveryPort(
                commands, localManager, localToRemote, remoteObservations,
                prs, tasks, publishStore, json,
                Clock.systemUTC());
        CleanupOperationResultDelivery cleanup = new CleanupOperationResultDelivery(
                cleanupStore, cleanupCompletion, Clock.systemUTC());
        RemoteFeedbackTurnResultDeliveryPort remoteTurn =
                new RemoteFeedbackTurnResultDeliveryPort(codec, remoteFeedbackRuntime);
        RemoteFeedbackValidationResultDeliveryPort remoteValidation =
                new RemoteFeedbackValidationResultDeliveryPort(remoteFeedbackRuntime);
        RemoteFeedbackBrainResultDeliveryPort remoteBrain =
                new RemoteFeedbackBrainResultDeliveryPort(remoteFeedbackRuntime);
        ExecutionPorts.ResultDeliveryPort remoteBrainDelivery =
                (owner, fence, result) -> remoteBrain.deliver(
                        codec.decode(owner, fence, result));
        ThreadTurnResultDeliveryPort threadTurns =
                new ThreadTurnResultDeliveryPort(
                        trunks, codec, json, Clock.systemUTC());
        RemoteObservationResultDeliveryPort observations =
                new RemoteObservationResultDeliveryPort(remoteObservations);
        RemoteCiRerunResultDeliveryPort ciRerun =
                new RemoteCiRerunResultDeliveryPort(remoteCi);
        RemoteCiEffectResultDeliveryPort ciEffects =
                new RemoteCiEffectResultDeliveryPort(remoteCi);
        BranchSyncResultDeliveryPort branches =
                new BranchSyncResultDeliveryPort(branchSync);
        RemoteRepairTurnResultDeliveryPort repairTurns =
                new RemoteRepairTurnResultDeliveryPort(codec, remoteRepairTurns);
        MergeResultDeliveryPort merge = new MergeResultDeliveryPort(
                commands, remoteManager, mergeOperations, json);
        ReviewAssignmentTurnResultDeliveryPort reviews =
                new ReviewAssignmentTurnResultDeliveryPort(
                        reviewAssignmentTurns, json, Clock.systemUTC(),
                        reviewContinuation::getObject);
        return new ResultDeliveryRouter(Map.ofEntries(
                Map.entry(PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE,
                        planningBase),
                Map.entry(PlanRuntimeCoordinator.PROVISION_CALLBACK, plan),
                Map.entry(PlanRuntimeCoordinator.TURN_CALLBACK, taskTurns),
                Map.entry(LocalDevelopmentRuntimeCoordinator.TURN_CALLBACK,
                        localDelivery),
                Map.entry(LocalValidationOperationHandler.CALLBACK_ROUTE, validation),
                Map.entry(PublishOperationHandler.CALLBACK_ROUTE, publish),
                Map.entry(CleanupOperationHandler.CALLBACK_ROUTE, cleanup),
                Map.entry(RemoteFeedbackRuntimeCoordinator.TURN_CALLBACK, remoteTurn),
                Map.entry(RemoteFeedbackRuntimeCoordinator.VALIDATION_CALLBACK,
                        remoteValidation),
                Map.entry(RemoteFeedbackRuntimeCoordinator.BRAIN_CALLBACK,
                        remoteBrainDelivery),
                Map.entry(RemoteDevelopmentRuntimeCoordinator.EFFECT_CALLBACK,
                        new RemoteFeedbackEffectResultDeliveryPort(remoteRuntime)),
                Map.entry(RemoteDevelopmentRuntimeCoordinator.MARK_READY_CALLBACK,
                        new RemoteMarkReadyResultDeliveryPort(remoteRuntime)),
                Map.entry(ThreadTurnOperationHandler.CALLBACK_ROUTE, threadTurns),
                Map.entry(TaskOutcomeSummaryResultDeliveryPort.CALLBACK_ROUTE,
                        taskTurns),
                Map.entry(RemoteObservationOperationHandler.CALLBACK_ROUTE,
                        observations),
                Map.entry("REMOTE_CI_RERUN_RESULT", ciRerun),
                Map.entry("REMOTE_CI_VALIDATION_RESULT", ciEffects),
                Map.entry("REMOTE_CI_PUSH_RESULT", ciEffects),
                Map.entry(RemoteRepairTurnRuntime.CI_STAGE_CALLBACK, repairTurns),
                Map.entry(RemoteRepairTurnRuntime.CI_BRAIN_CALLBACK, repairTurns),
                Map.entry(RemoteRepairTurnRuntime.BRANCH_STAGE_CALLBACK,
                        repairTurns),
                Map.entry(RemoteRepairTurnRuntime.BRANCH_BRAIN_CALLBACK,
                        repairTurns),
                Map.entry("BRANCH_SYNC_FETCH_RESULT", branches),
                Map.entry("BRANCH_SYNC_REBASE_RESULT", branches),
                Map.entry("BRANCH_SYNC_VALIDATION_RESULT", branches),
                Map.entry("BRANCH_SYNC_PUSH_RESULT", branches),
                Map.entry(ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE,
                        reviews),
                Map.entry(MergeOperationHandler.CALLBACK_ROUTE, merge)));
    }

    @Bean
    @ConditionalOnMissingBean(AgentTurnProviderSession.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public AgentTurnProviderSession v2AgentTurnProviderSession(
            CredentialService credentials,
            Ds4LifecycleService ds4,
            TurnRunner turns,
            ObjectMapper json)
    {
        AgentTurnProviderSession cli = new CliAgentTurnProviderSession(json);
        AgentTurnProviderSession api = new ApiAgentTurnProviderSession(
                new CredentialApiProviderResolver(credentials, ds4),
                new LoopbackOwnerMcpClient(json), turns, json);
        return new RoutingAgentTurnProviderSession(cli, api);
    }

    @Bean
    @ConditionalOnMissingBean(ProvisionTaskOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ProvisionTaskOperationHandler v2ProvisionTaskOperationHandler(
            SqliteProvisionTaskOperationStore operations,
            GitRunnerProvisioningGit git,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        return new ProvisionTaskOperationHandler(operations, git, writers, json);
    }

    @Bean
    @ConditionalOnMissingBean(AgentTurnOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public AgentTurnOperationHandler v2AgentTurnOperationHandler(
            SqliteAgentTurnOperationStore operations,
            AgentTurnProviderSession provider,
            WorktreeWriterLeaseManager writers,
            ActiveAgentContextRegistry activeContexts,
            ToolExposurePolicy tools,
            ObjectMapper json)
    {
        return new AgentTurnOperationHandler(
                operations, provider, writers, activeContexts, tools, json);
    }

    @Bean
    @ConditionalOnMissingBean(ThreadTurnOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ThreadTurnOperationHandler v2ThreadTurnOperationHandler(
            SqliteThreadTurnOperationStore operations,
            AgentTurnProviderSession provider,
            ActiveAgentContextRegistry activeContexts,
            ToolExposurePolicy tools,
            ObjectMapper json)
    {
        return new ThreadTurnOperationHandler(
                operations, provider, activeContexts, tools, json,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(PlanningBaseRefreshOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public PlanningBaseRefreshOperationHandler v2PlanningBaseRefreshOperationHandler(
            SqlitePlanningBaseTurnStore operations,
            WorktreeService worktrees,
            ObjectMapper json)
    {
        return new PlanningBaseRefreshOperationHandler(
                operations, worktrees::refreshPlanningWorktree, json,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(ReviewAssignmentTurnOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ReviewAssignmentTurnOperationHandler v2ReviewAssignmentTurnOperationHandler(
            SqliteReviewAssignmentTurnStore operations,
            AgentTurnProviderSession provider,
            ObjectMapper json)
    {
        return new ReviewAssignmentTurnOperationHandler(
                operations, provider, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(RemoteObservationOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public RemoteObservationOperationHandler v2RemoteObservationOperationHandler(
            SqliteRemoteRuntimeStore operations,
            GitHubRemoteObserver observer,
            ObjectMapper json)
    {
        return new RemoteObservationOperationHandler(operations, observer, json);
    }

    @Bean
    @ConditionalOnMissingBean(RemoteEffectOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public RemoteEffectOperationHandler v2RemoteEffectOperationHandler(
            SqliteRemoteRuntimeStore operations,
            GitHubRemoteEffects effects,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        return new RemoteEffectOperationHandler(
                operations, effects, writers, json);
    }

    @Bean
    @ConditionalOnMissingBean(MergeOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public MergeOperationHandler v2MergeOperationHandler(
            SqliteMergeOperationStore operations,
            GitHubMergeEffects effects,
            ObjectMapper json,
            @Value("${bytequay.development-flow.merge.observation-poll-ms:20000}")
            long observationPollMs)
    {
        return new MergeOperationHandler(
                operations, effects, json, Clock.systemUTC(),
                Duration.ofMillis(observationPollMs));
    }

    @Bean
    @ConditionalOnMissingBean(LocalValidationOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public LocalValidationOperationHandler v2LocalValidationOperationHandler(
            SqliteLocalDevelopmentRuntimeStore store,
            List<ValidationCheck> checks,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json)
    {
        return new LocalValidationOperationHandler(
                store, checks, fingerprints, git, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(PublishOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public PublishOperationHandler v2PublishOperationHandler(
            SqlitePublishOperationStore operations,
            GitHubPublishEffects effects,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        return new PublishOperationHandler(
                operations, effects, writers, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CleanupOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public CleanupOperationHandler v2CleanupOperationHandler(
            SqliteCleanupOperationStore operations,
            SqliteCleanupEffects effects,
            CleanupQuiescenceHandoff quiescence)
    {
        return new CleanupOperationHandler(
                operations, effects, quiescence, Clock.systemUTC(),
                Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @Bean
    @ConditionalOnMissingBean(RemoteFeedbackValidationOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public RemoteFeedbackValidationOperationHandler
            v2RemoteFeedbackValidationOperationHandler(
                    SqliteRemoteFeedbackLoopStore store,
                    List<ValidationCheck> checks,
                    CodeFingerprints fingerprints,
                    GitRunner git,
                    ObjectMapper json)
    {
        return new RemoteFeedbackValidationOperationHandler(
                store, checks, fingerprints, git, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(RemoteFeedbackEffectOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public RemoteFeedbackEffectOperationHandler v2RemoteFeedbackEffectOperationHandler(
            SqliteRemoteFeedbackEffectOperationStore operations,
            RemoteFeedbackEffectOperationHandler.EffectGateway effects,
            ObjectMapper json)
    {
        return new RemoteFeedbackEffectOperationHandler(
                operations, effects, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(RemoteMarkReadyOperationHandler.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public RemoteMarkReadyOperationHandler v2RemoteMarkReadyOperationHandler(
            SqliteRemoteMarkReadyOperationStore operations,
            RemoteMarkReadyOperationHandler.MarkReadyGateway github,
            ObjectMapper json)
    {
        return new RemoteMarkReadyOperationHandler(
                operations, github, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionPorts.OperationHandlerRegistry.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ExecutionPorts.OperationHandlerRegistry v2OperationHandlers(
            ProvisionTaskOperationHandler provisioning,
            AgentTurnOperationHandler agentTurns,
            ThreadTurnOperationHandler threadTurns,
            PlanningBaseRefreshOperationHandler planningBase,
            ReviewAssignmentTurnOperationHandler reviewTurns,
            LocalValidationOperationHandler localValidation,
            PublishOperationHandler publish,
            CleanupOperationHandler cleanup,
            RemoteFeedbackValidationOperationHandler remoteValidation,
            RemoteFeedbackEffectOperationHandler remoteEffects,
            RemoteMarkReadyOperationHandler markReady,
            RemoteObservationOperationHandler observations,
            RemoteEffectOperationHandler remoteFiniteEffects,
            MergeOperationHandler merge)
    {
        Map<String, ExecutionPorts.OperationHandler> handlers = Map.ofEntries(
                Map.entry(ProvisionTaskOperationHandler.OPERATION_KIND, provisioning),
                Map.entry(AgentTurnOperationHandler.TASK_OPERATION_KIND, agentTurns),
                Map.entry(
                        AgentTurnOperationHandler.TASK_OUTCOME_SUMMARY_OPERATION_KIND,
                        agentTurns),
                Map.entry(AgentTurnOperationHandler.STAGE_OPERATION_KIND, agentTurns),
                Map.entry(ThreadTurnOperationHandler.OPERATION_KIND, threadTurns),
                Map.entry(PlanningBaseRefreshOperationHandler.OPERATION_KIND,
                        planningBase),
                Map.entry(ReviewAssignmentTurnOperationHandler.OPERATION_KIND,
                        reviewTurns),
                Map.entry(LocalValidationOperationHandler.OPERATION_KIND,
                        localValidation),
                Map.entry(PublishOperationHandler.OPERATION_KIND, publish),
                Map.entry(CleanupOperationHandler.OPERATION_KIND, cleanup),
                Map.entry(RemoteFeedbackValidationOperationHandler.OPERATION_KIND,
                        remoteValidation),
                Map.entry(RemoteFeedbackEffectOperationHandler.OPERATION_KIND,
                        remoteEffects),
                Map.entry(RemoteMarkReadyOperationHandler.OPERATION_KIND, markReady),
                Map.entry(RemoteObservationOperationHandler.OPERATION_KIND,
                        observations),
                Map.entry(RemoteEffectOperationHandler.RERUN_CI,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.VALIDATE_CI_REPAIR,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.PUSH_CI_REPAIR,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.FETCH_BRANCH,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.REBASE_BRANCH,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.VALIDATE_BRANCH,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.PUSH_BRANCH,
                        remoteFiniteEffects),
                Map.entry(MergeOperationHandler.OPERATION_KIND, merge));
        return operationKind -> {
            ExecutionPorts.OperationHandler handler = handlers.get(
                    requireNonNull(operationKind, "operationKind is null"));
            if (handler == null) {
                throw new IllegalArgumentException(
                        "Unsupported V2 operation kind: " + operationKind);
            }
            return handler;
        };
    }

    @Bean
    @ConditionalOnMissingBean(CapacityManager.CapacityPolicySource.class)
    public CapacityManager.CapacityPolicySource developmentFlowCapacityPolicy(
            ThreadStore threads,
            ThreadSettingsStore settings,
            @Value("${bytequay.development-flow.capacity.default-workspace-running-tasks:4}")
            int defaultWorkspaceLimit,
            @Value("${bytequay.development-flow.capacity.default-trunk-running-tasks:4}")
            int defaultTrunkLimit,
            @Value("${bytequay.development-flow.capacity.validation:4}") int validationLimit,
            @Value("${bytequay.development-flow.capacity.review:6}") int reviewLimit,
            @Value("${bytequay.development-flow.capacity.local-git:4}") int localGitLimit,
            @Value("${bytequay.development-flow.capacity.github:6}") int githubLimit,
            @Value("${bytequay.development-flow.capacity.remote-observation:8}")
            int remoteObservationLimit,
            @Value("${bytequay.development-flow.capacity.merge:2}") int mergeLimit,
            @Value("${bytequay.development-flow.capacity.cleanup:4}") int cleanupLimit)
    {
        return new DevelopmentFlowCapacityPolicySource(
                threads,
                settings,
                defaultWorkspaceLimit,
                defaultTrunkLimit,
                Map.of(
                        VALIDATION, validationLimit,
                        REVIEW, reviewLimit,
                        LOCAL_GIT, localGitLimit,
                        GITHUB, githubLimit,
                        REMOTE_OBSERVATION, remoteObservationLimit,
                        MERGE, mergeLimit,
                        CLEANUP, cleanupLimit));
    }

    @Bean
    @ConditionalOnMissingBean(CapacityManager.class)
    public CapacityManager capacityManager(
            CapacityManager.CapacityLeaseStore leases,
            CapacityManager.CapacityPolicySource policies)
    {
        return new CapacityManager(
                leases, policies, Clock.systemUTC(), Duration.ofSeconds(30));
    }

    @Bean
    @ConditionalOnMissingBean(LegacyCapacityBridge.class)
    public LegacyCapacityBridge legacyCapacityBridge(CapacityManager capacityManager)
    {
        return new LegacyCapacityBridge(capacityManager);
    }

    @Bean
    @ConditionalOnMissingBean(LegacyCapacityLeaseMaintainer.class)
    public LegacyCapacityLeaseMaintainer legacyCapacityLeaseMaintainer(
            LegacyCapacityBridge bridge)
    {
        return new LegacyCapacityLeaseMaintainer(bridge);
    }

    @Bean
    @ConditionalOnMissingBean(WorktreeWriterLeaseManager.class)
    public WorktreeWriterLeaseManager worktreeWriterLeaseManager(
            WorktreeWriterLeaseManager.Store store)
    {
        return new WorktreeWriterLeaseManager(store, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public V2TaskControlService v2TaskControlService(
            TaskManager tasks,
            TaskManager.Store store,
            ExecutionDispatcher dispatcher,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            JdbcTemplate jdbc)
    {
        return new V2TaskControlService(
                tasks, store, dispatcher, ciRepair, jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(PlanningBaseTurnRuntime.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public PlanningBaseTurnRuntime v2PlanningBaseTurnRuntime(
            TrunkManager trunks,
            TrunkManager.Store trunkStore,
            SqlitePlanningBaseTurnStore planningStore,
            ThreadTurnHandoff turns,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ObjectMapper json)
    {
        return new PlanningBaseTurnRuntime(
                trunks, trunkStore, planningStore, turns, repositories,
                watchedRepos, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(V2ThreadControlService.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public V2ThreadControlService v2ThreadControlService(
            PlanningBaseTurnRuntime planning,
            ThreadTurnProjection projection,
            ExecutionDispatcher dispatcher,
            ThreadEngineOverrides engines,
            RoleRegistry roles,
            SessionKnowledgeProvider knowledge)
    {
        return new V2ThreadControlService(
                planning, projection, dispatcher, engines, roles, knowledge);
    }

    @Bean
    @ConditionalOnMissingBean(ReviewAssignmentTurnRuntime.class)
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ReviewAssignmentTurnRuntime v2ReviewAssignmentTurnRuntime(
            SqliteReviewAssignmentTurnStore store,
            ReviewProviderEndpoints providers,
            ExecutionDispatcher dispatcher,
            ObjectMapper json,
            @Value("${server.port:8080}") int serverPort)
    {
        return new ReviewAssignmentTurnRuntime(
                store, providers, dispatcher::requestCancel,
                json, Clock.systemUTC(), serverPort);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(
            name = "bytequay.development-flow.v2-dispatch-enabled",
            havingValue = "true")
    public ExecutionDispatcher v2ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.DispatchWakeStore wakes,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            List<ExecutionPorts.MaintenanceWork> maintenanceWork)
    {
        return new ExecutionDispatcher(
                capacityManager,
                tickets,
                wakes,
                handlers,
                resultDelivery,
                evidence,
                Clock.systemUTC(),
                new ExecutionDispatcher.Config(
                        "v2-dispatcher",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(5),
                        3,
                        100),
                maintenanceWork);
    }
}
