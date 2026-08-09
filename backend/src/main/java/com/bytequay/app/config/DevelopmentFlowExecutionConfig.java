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
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
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
import com.bytequay.app.developmentflow.execution.publish.GitLocalPublishBaseSyncEffects;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.SqlitePublishOperationStore;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler;
import com.bytequay.app.developmentflow.execution.quality.SqliteQualityIssuePublishStore;
import com.bytequay.app.developmentflow.execution.quality.V2QualityIssuePublishRuntime;
import com.bytequay.app.developmentflow.execution.remote.CompositeUserRemoteActionStore;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteEffects;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteObserver;
import com.bytequay.app.developmentflow.execution.remote.GitHubReviewBuildCommentGateway;
import com.bytequay.app.developmentflow.execution.remote.GitHubUserRemoteActionGateway;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.RemoteMarkReadyOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.ReviewPublicationOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteFeedbackEffectOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteMarkReadyOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewBuildCommentStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewPassPublicationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairRuntime;
import com.bytequay.app.developmentflow.persistence.SqliteAgentTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteReviewAssignmentTurnStore;
import com.bytequay.app.developmentflow.persistence.SqliteThreadTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalPublishBaseSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.LocalValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.ManualPrValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.ManualPrValidationResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.MergeResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PlanResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackBrainResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.V2ReadinessAssistanceRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePublishResultStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.task.TaskBrainConversationResultDeliveryPort;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskPolicyRevisionRedriver;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.developmentflow.task.creation.V2TaskCreationService;
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
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.bytequay.app.developmentflow.userwait.V2UserWaitResultDeliveryPort;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.review.ReviewSessionSnapshotOperationHandler;
import com.bytequay.app.service.review.ReviewSessionSnapshotResultDeliveryPort;
import com.bytequay.app.service.review.TaskReviewRoundSnapshotOperationHandler;
import com.bytequay.app.service.review.TaskReviewRoundSnapshotResultDeliveryPort;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler;
import com.bytequay.app.service.review.TaskReviewSnapshotResultDeliveryPort;
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
 * Shared capacity wiring is always active for the permanent V2 runtime.
 * Dispatch requires explicit Remote effect gateways and fails closed when
 * either external adapter is absent.
 */
@Configuration(proxyBeanMethods = false)
public class DevelopmentFlowExecutionConfig
{
    @Bean
    @Primary
    public ExecutionPorts.ResultDeliveryPort v2ResultDelivery(
            PlanningBaseTurnRuntime planningBase,
            PlanRuntimeCoordinator planRuntime,
            LocalDevelopmentRuntimeCoordinator localRuntime,
            LocalPublishBaseSyncRuntimeCoordinator localPublishBaseSync,
            WorktreeQuarantineRepairRuntime worktreeRepairs,
            RemoteFeedbackRuntimeCoordinator remoteFeedbackRuntime,
            RemoteDevelopmentRuntimeCoordinator remoteRuntime,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager localManager,
            LocalToRemoteHandoff localToRemote,
            RemoteObservationRuntimeCoordinator remoteObservations,
            PRService prs,
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
            V2UserWaitStore userWaits,
            TaskOutcomeSummaryRuntime outcomeSummaryRuntime,
            TaskBrainConversationRuntime taskBrainConversation,
            V2UserRemoteActionRuntime userRemoteActions,
            V2QualityIssuePublishRuntime qualityIssuePublishes,
            V2ReadinessAssistanceRuntime readinessAssistance,
            ManualPrValidationResultDeliveryPort manualValidation,
            TaskReviewSnapshotResultDeliveryPort taskReviewSnapshots,
            TaskReviewRoundSnapshotResultDeliveryPort taskReviewRoundSnapshots,
            ReviewSessionSnapshotResultDeliveryPort reviewSessionSnapshots,
            ObjectProvider<InvestigationReviewService> reviewContinuation,
            JdbcTemplate jdbc,
            ObjectMapper json)
    {
        PlanResultDeliveryPort plan = new PlanResultDeliveryPort(planRuntime);
        AgentTurnOwnerResultCodec codec = new AgentTurnOwnerResultCodec(json);
        LocalDevelopmentResultDeliveryPort localDelivery =
                new LocalDevelopmentResultDeliveryPort(codec, localRuntime);
        ExecutionPorts.ResultDeliveryPort brainDelivery =
                (owner, fence, result) -> localRuntime.deliverBrainTurn(
                        codec.decode(owner, fence, result));
        ExecutionPorts.ResultDeliveryPort taskTurns =
                new TaskTurnResultDeliveryRouter(jdbc, Map.of(
                        "PLAN_DRAFT", plan,
                        "PLAN_SELF_REVIEW", plan,
                        "DEVELOPMENT_BRAIN_REVIEW", brainDelivery,
                        "DEVELOPMENT_BRAIN_RESULT_REPAIR", brainDelivery,
                        "TASK_BRAIN_CONVERSATION",
                        new TaskBrainConversationResultDeliveryPort(
                                codec, taskBrainConversation),
                        "TASK_COMPLETION_SUMMARY",
                        new TaskOutcomeSummaryResultDeliveryPort(
                                outcomeSummaries, outcomeSummaryRuntime,
                                codec, json, Clock.systemUTC())));
        ExecutionPorts.ResultDeliveryPort validation =
                localRuntime::deliverValidation;
        PublishResultDeliveryPort publish = new PublishResultDeliveryPort(
                commands, localManager, localToRemote, remoteObservations,
                prs, publishStore, localPublishBaseSync, json,
                Clock.systemUTC());
        ExecutionPorts.ResultDeliveryPort localBaseSync =
                localPublishBaseSync;
        CleanupOperationResultDelivery cleanup = new CleanupOperationResultDelivery(
                cleanupStore, cleanupCompletion, Clock.systemUTC());
        RemoteFeedbackTurnResultDeliveryPort remoteTurn =
                new RemoteFeedbackTurnResultDeliveryPort(codec, remoteFeedbackRuntime);
        ExecutionPorts.ResultDeliveryPort remoteValidation =
                remoteFeedbackRuntime::deliverValidation;
        RemoteFeedbackBrainResultDeliveryPort remoteBrain =
                new RemoteFeedbackBrainResultDeliveryPort(remoteFeedbackRuntime);
        ExecutionPorts.ResultDeliveryPort remoteBrainDelivery =
                (owner, fence, result) -> remoteBrain.deliver(
                        codec.decode(owner, fence, result));
        ThreadTurnResultDeliveryPort threadTurns =
                new ThreadTurnResultDeliveryPort(
                        trunks, codec, json, Clock.systemUTC());
        ExecutionPorts.ResultDeliveryPort observations =
                remoteObservations::deliver;
        ExecutionPorts.ResultDeliveryPort ciRerun = remoteCi::deliverRerun;
        ExecutionPorts.ResultDeliveryPort ciEffects = remoteCi::deliverEffect;
        ExecutionPorts.ResultDeliveryPort branches = branchSync::deliver;
        RemoteRepairTurnResultDeliveryPort repairTurns =
                new RemoteRepairTurnResultDeliveryPort(codec, remoteRepairTurns);
        ExecutionPorts.ResultDeliveryPort repairAdoption =
                remoteRepairTurns::deliverAdoption;
        MergeResultDeliveryPort merge = new MergeResultDeliveryPort(
                commands, remoteManager, mergeOperations, json);
        ReviewAssignmentTurnResultDeliveryPort reviews =
                new ReviewAssignmentTurnResultDeliveryPort(
                        reviewAssignmentTurns, json, Clock.systemUTC(),
                        () -> reviewContinuation.getObject()::resumeAfter);
        ResultDeliveryRouter routes = new ResultDeliveryRouter(Map.ofEntries(
                Map.entry(PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE,
                        planningBase),
                Map.entry(PlanRuntimeCoordinator.PROVISION_CALLBACK, plan),
                Map.entry(PlanRuntimeCoordinator.TURN_CALLBACK, taskTurns),
                Map.entry(LocalDevelopmentRuntimeCoordinator.TURN_CALLBACK,
                        localDelivery),
                Map.entry(LocalValidationOperationHandler.CALLBACK_ROUTE, validation),
                Map.entry(ManualPrValidationOperationHandler.CALLBACK_ROUTE,
                        manualValidation),
                Map.entry(TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE,
                        taskReviewSnapshots),
                Map.entry(TaskReviewRoundSnapshotOperationHandler.CALLBACK_ROUTE,
                        taskReviewRoundSnapshots),
                Map.entry(ReviewSessionSnapshotOperationHandler.CALLBACK_ROUTE,
                        reviewSessionSnapshots),
                Map.entry(PublishOperationHandler.CALLBACK_ROUTE, publish),
                Map.entry(LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK,
                        localBaseSync),
                Map.entry(LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK,
                        localBaseSync),
                Map.entry(
                        WorktreeQuarantineRepairOperationHandler.CALLBACK_ROUTE,
                        worktreeRepairs),
                Map.entry(CleanupOperationHandler.CALLBACK_ROUTE, cleanup),
                Map.entry(RemoteFeedbackRuntimeCoordinator.TURN_CALLBACK, remoteTurn),
                Map.entry(RemoteFeedbackRuntimeCoordinator.VALIDATION_CALLBACK,
                        remoteValidation),
                Map.entry(RemoteFeedbackRuntimeCoordinator.BRAIN_CALLBACK,
                        remoteBrainDelivery),
                Map.entry(RemoteDevelopmentRuntimeCoordinator.EFFECT_CALLBACK,
                        remoteRuntime::deliverEffect),
                Map.entry(RemoteDevelopmentRuntimeCoordinator.MARK_READY_CALLBACK,
                        remoteRuntime::deliverMarkReady),
                Map.entry(ThreadTurnOperationHandler.CALLBACK_ROUTE, threadTurns),
                Map.entry(TaskOutcomeSummaryResultDeliveryPort.CALLBACK_ROUTE,
                        taskTurns),
                Map.entry(RemoteObservationOperationHandler.CALLBACK_ROUTE,
                        observations),
                Map.entry("REMOTE_CI_RERUN_RESULT", ciRerun),
                Map.entry("REMOTE_CI_VALIDATION_RESULT", ciEffects),
                Map.entry("REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT",
                        ciEffects),
                Map.entry("REMOTE_CI_PUSH_RESULT", ciEffects),
                Map.entry(RemoteRepairTurnRuntime.CI_STAGE_CALLBACK, repairTurns),
                Map.entry(RemoteRepairTurnRuntime.CI_BRAIN_CALLBACK, repairTurns),
                Map.entry(RemoteRepairTurnRuntime.BRANCH_STAGE_CALLBACK,
                        repairTurns),
                Map.entry(RemoteRepairTurnRuntime.BRANCH_BRAIN_CALLBACK,
                        repairTurns),
                Map.entry(RemoteRepairTurnRuntime.STEERING_CALLBACK,
                        repairTurns),
                Map.entry(RemoteRepairTurnRuntime.NORMALIZATION_CALLBACK,
                        repairTurns),
                Map.entry(
                        RemoteRepairCommitAdoptionOperationHandler.CALLBACK_ROUTE,
                        repairAdoption),
                Map.entry("BRANCH_SYNC_FETCH_RESULT", branches),
                Map.entry("BRANCH_SYNC_REBASE_RESULT", branches),
                Map.entry("BRANCH_SYNC_VALIDATION_RESULT", branches),
                Map.entry("BRANCH_SYNC_PUSH_RESULT", branches),
                Map.entry(ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE,
                        reviews),
                Map.entry(UserRemoteActionOperationHandler.CALLBACK_ROUTE,
                        userRemoteActions),
                Map.entry(UserRemoteActionOperationHandler.EXTERNAL_CALLBACK_ROUTE,
                        userRemoteActions),
                Map.entry(ReviewBuildCommentOperationHandler.CALLBACK_ROUTE,
                        userRemoteActions),
                Map.entry(ReviewBuildCommentOperationHandler
                                .REVIEW_PASS_CALLBACK_ROUTE,
                        userRemoteActions),
                Map.entry(QualityIssuePublishOperationHandler.CALLBACK_ROUTE,
                        qualityIssuePublishes),
                Map.entry(RemoteFeedbackEffectOperationHandler
                                .READINESS_ASSISTANCE_CALLBACK_ROUTE,
                        readinessAssistance),
                Map.entry(MergeOperationHandler.CALLBACK_ROUTE, merge)));
        return new V2UserWaitResultDeliveryPort(
                userWaits, routes, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(V2ReadinessAssistanceRuntime.class)
    public V2ReadinessAssistanceRuntime v2ReadinessAssistanceRuntime(
            SqliteReadinessAssistanceStore store,
            TaskCommandExecutor commands,
            ObjectMapper json)
    {
        return new V2ReadinessAssistanceRuntime(store, commands, json);
    }

    @Bean
    @ConditionalOnMissingBean(AgentTurnProviderSession.class)
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
    public ProvisionTaskOperationHandler v2ProvisionTaskOperationHandler(
            SqliteProvisionTaskOperationStore operations,
            GitRunnerProvisioningGit git,
            WorktreeWriterLeaseManager writers,
            PullRequestRepository pullRequests,
            PatResolver pats,
            ObjectMapper json)
    {
        return new ProvisionTaskOperationHandler(
                operations, git, writers, pullRequests, pats, json);
    }

    @Bean
    @ConditionalOnMissingBean(AgentTurnOperationHandler.class)
    public AgentTurnOperationHandler v2AgentTurnOperationHandler(
            SqliteAgentTurnOperationStore operations,
            AgentTurnProviderSession provider,
            WorktreeWriterLeaseManager writers,
            CodeFingerprints fingerprints,
            GitRunner git,
            ActiveAgentContextRegistry activeContexts,
            ToolExposurePolicy tools,
            ObjectMapper json)
    {
        return new AgentTurnOperationHandler(
                operations, provider, writers, fingerprints, git,
                activeContexts, tools, json);
    }

    @Bean
    @ConditionalOnMissingBean(ThreadTurnOperationHandler.class)
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
    public RemoteObservationOperationHandler v2RemoteObservationOperationHandler(
            SqliteRemoteRuntimeStore operations,
            GitHubRemoteObserver observer,
            ObjectMapper json)
    {
        return new RemoteObservationOperationHandler(operations, observer, json);
    }

    @Bean
    @ConditionalOnMissingBean(RemoteEffectOperationHandler.class)
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
    @ConditionalOnMissingBean(RemoteRepairCommitAdoptionOperationHandler.class)
    public RemoteRepairCommitAdoptionOperationHandler
            v2RemoteRepairCommitAdoptionOperationHandler(
                    SqliteRemoteRepairNormalizationStore operations,
                    WorktreeWriterLeaseManager writers,
                    GitRunner git,
                    CodeFingerprints fingerprints,
                    ObjectMapper json)
    {
        return new RemoteRepairCommitAdoptionOperationHandler(
                operations, writers, git, fingerprints, json,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(MergeOperationHandler.class)
    public MergeOperationHandler v2MergeOperationHandler(
            SqliteMergeOperationStore operations,
            GitHubMergeEffects effects,
            ObjectMapper json)
    {
        return new MergeOperationHandler(
                operations, effects, json, Clock.systemUTC(),
                Duration.ofSeconds(20));
    }

    @Bean
    @ConditionalOnMissingBean(LocalValidationOperationHandler.class)
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
    @ConditionalOnMissingBean(GitLocalPublishBaseSyncEffects.class)
    public GitLocalPublishBaseSyncEffects gitLocalPublishBaseSyncEffects(
            GitRunner git, CodeFingerprints fingerprints)
    {
        return new GitLocalPublishBaseSyncEffects(git, fingerprints);
    }

    @Bean
    @ConditionalOnMissingBean(LocalPublishBaseSyncOperationHandler.class)
    public LocalPublishBaseSyncOperationHandler
            localPublishBaseSyncOperationHandler(
                    SqliteLocalPublishBaseSyncStore operations,
                    GitLocalPublishBaseSyncEffects effects,
                    WorktreeWriterLeaseManager writers,
                    ObjectMapper json)
    {
        return new LocalPublishBaseSyncOperationHandler(
                operations, effects, writers, json);
    }

    @Bean
    @ConditionalOnMissingBean(WorktreeQuarantineRepairRuntime.class)
    public WorktreeQuarantineRepairRuntime worktreeQuarantineRepairRuntime(
            TaskCommandExecutor commands,
            SqliteWorktreeQuarantineRepairStore operations,
            ObjectMapper json)
    {
        return new WorktreeQuarantineRepairRuntime(
                commands, operations, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(WorktreeQuarantineRepairOperationHandler.class)
    public WorktreeQuarantineRepairOperationHandler
            worktreeQuarantineRepairOperationHandler(
                    SqliteWorktreeQuarantineRepairStore operations,
                    WorktreeWriterLeaseManager writers,
                    GitRunner git,
                    CodeFingerprints fingerprints,
                    ObjectMapper json)
    {
        return new WorktreeQuarantineRepairOperationHandler(
                operations, writers, git, fingerprints, json,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CleanupOperationHandler.class)
    public CleanupOperationHandler v2CleanupOperationHandler(
            SqliteCleanupOperationStore operations,
            SqliteCleanupEffects effects,
            CleanupQuiescenceHandoff quiescence,
            WorktreeWriterLeaseManager writers)
    {
        return new CleanupOperationHandler(
                operations, effects, quiescence, writers, Clock.systemUTC(),
                Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @Bean
    @ConditionalOnMissingBean(RemoteFeedbackValidationOperationHandler.class)
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
    public RemoteFeedbackEffectOperationHandler v2RemoteFeedbackEffectOperationHandler(
            SqliteRemoteFeedbackEffectOperationStore operations,
            RemoteFeedbackEffectOperationHandler.EffectGateway effects,
            ObjectMapper json)
    {
        return new RemoteFeedbackEffectOperationHandler(
                operations, effects, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(UserRemoteActionOperationHandler.class)
    public UserRemoteActionOperationHandler v2UserRemoteActionOperationHandler(
            SqliteUserRemoteActionStore taskActions,
            SqliteExternalPrActionStore externalActions,
            GitHubUserRemoteActionGateway github,
            ObjectMapper json)
    {
        return new UserRemoteActionOperationHandler(
                new CompositeUserRemoteActionStore(taskActions, externalActions),
                github, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(V2UserRemoteActionRuntime.class)
    public V2UserRemoteActionRuntime v2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore operations,
            SqliteExternalPrActionStore externalActions,
            SqliteReviewBuildCommentStore reviewBuildComments,
            SqliteReviewPassPublicationStore reviewPassPublications,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews)
    {
        return new V2UserRemoteActionRuntime(
                operations, externalActions, reviewBuildComments,
                reviewPassPublications,
                prs, json,
                investigationReviews);
    }

    @Bean
    @ConditionalOnMissingBean(ReviewBuildCommentOperationHandler.class)
    public ReviewBuildCommentOperationHandler reviewBuildCommentOperationHandler(
            ReviewPublicationOperationStore operations,
            GitHubReviewBuildCommentGateway github,
            ObjectMapper json)
    {
        return new ReviewBuildCommentOperationHandler(
                operations, github, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(QualityIssuePublishOperationHandler.class)
    public QualityIssuePublishOperationHandler qualityIssuePublishOperationHandler(
            SqliteQualityIssuePublishStore operations,
            QualityIssuePublishOperationHandler.Gateway github,
            ObjectMapper json)
    {
        return new QualityIssuePublishOperationHandler(
                operations, github, json, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(RemoteMarkReadyOperationHandler.class)
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
    public ExecutionPorts.OperationHandlerRegistry v2OperationHandlers(
            ProvisionTaskOperationHandler provisioning,
            AgentTurnOperationHandler agentTurns,
            ThreadTurnOperationHandler threadTurns,
            PlanningBaseRefreshOperationHandler planningBase,
            ReviewAssignmentTurnOperationHandler reviewTurns,
            LocalValidationOperationHandler localValidation,
            ManualPrValidationOperationHandler manualValidation,
            TaskReviewSnapshotOperationHandler taskReviewSnapshots,
            TaskReviewRoundSnapshotOperationHandler taskReviewRoundSnapshots,
            ReviewSessionSnapshotOperationHandler reviewSessionSnapshots,
            PublishOperationHandler publish,
            LocalPublishBaseSyncOperationHandler localPublishBaseSync,
            WorktreeQuarantineRepairOperationHandler worktreeRepairs,
            CleanupOperationHandler cleanup,
            RemoteFeedbackValidationOperationHandler remoteValidation,
            RemoteFeedbackEffectOperationHandler remoteEffects,
            UserRemoteActionOperationHandler userRemoteActions,
            ReviewBuildCommentOperationHandler reviewBuildComments,
            QualityIssuePublishOperationHandler qualityIssuePublishes,
            RemoteMarkReadyOperationHandler markReady,
            RemoteObservationOperationHandler observations,
            RemoteEffectOperationHandler remoteFiniteEffects,
            RemoteRepairCommitAdoptionOperationHandler repairAdoption,
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
                Map.entry(ManualPrValidationOperationHandler.OPERATION_KIND,
                        manualValidation),
                Map.entry(TaskReviewSnapshotOperationHandler.OPERATION_KIND,
                        taskReviewSnapshots),
                Map.entry(TaskReviewRoundSnapshotOperationHandler.OPERATION_KIND,
                        taskReviewRoundSnapshots),
                Map.entry(ReviewSessionSnapshotOperationHandler.OPERATION_KIND,
                        reviewSessionSnapshots),
                Map.entry(PublishOperationHandler.OPERATION_KIND, publish),
                Map.entry(LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                        localPublishBaseSync),
                Map.entry(LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                        localPublishBaseSync),
                Map.entry(WorktreeQuarantineRepairOperationHandler.OPERATION_KIND,
                        worktreeRepairs),
                Map.entry(CleanupOperationHandler.OPERATION_KIND, cleanup),
                Map.entry(RemoteFeedbackValidationOperationHandler.OPERATION_KIND,
                        remoteValidation),
                Map.entry(RemoteFeedbackEffectOperationHandler.OPERATION_KIND,
                        remoteEffects),
                Map.entry(RemoteFeedbackEffectOperationHandler
                                .READINESS_ASSISTANCE_OPERATION_KIND,
                        remoteEffects),
                Map.entry(UserRemoteActionOperationHandler.OPERATION_KIND,
                        userRemoteActions),
                Map.entry(UserRemoteActionOperationHandler.EXTERNAL_OPERATION_KIND,
                        userRemoteActions),
                Map.entry(ReviewBuildCommentOperationHandler.OPERATION_KIND,
                        reviewBuildComments),
                Map.entry(ReviewBuildCommentOperationHandler
                                .REVIEW_PASS_OPERATION_KIND,
                        reviewBuildComments),
                Map.entry(QualityIssuePublishOperationHandler.OPERATION_KIND,
                        qualityIssuePublishes),
                Map.entry(RemoteMarkReadyOperationHandler.OPERATION_KIND, markReady),
                Map.entry(RemoteObservationOperationHandler.OPERATION_KIND,
                        observations),
                Map.entry(RemoteEffectOperationHandler.RERUN_CI,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler.VALIDATE_CI_REPAIR,
                        remoteFiniteEffects),
                Map.entry(RemoteEffectOperationHandler
                                .REWRITE_VALIDATE_BASE_CI_REPAIR,
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
                Map.entry(
                        RemoteRepairCommitAdoptionOperationHandler.OPERATION_KIND,
                        repairAdoption),
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
            ObjectMapper mapper,
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
                mapper,
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
    @ConditionalOnMissingBean(WorktreeWriterLeaseManager.class)
    public WorktreeWriterLeaseManager worktreeWriterLeaseManager(
            WorktreeWriterLeaseManager.Store store)
    {
        return new WorktreeWriterLeaseManager(store, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(DispatchTicketControl.class)
    public DispatchTicketControl dispatchTicketControl(
            ExecutionPorts.DispatchTicketStore tickets,
            ObjectProvider<ExecutionDispatcher> dispatcher)
    {
        return new DispatchTicketControl(tickets, dispatcher);
    }

    @Bean
    @ConditionalOnMissingBean(V2TaskControlService.class)
    public V2TaskControlService v2TaskControlService(
            TaskManager tasks,
            TaskManager.Store store,
            DispatchTicketControl tickets,
            RemoteCiRepairRuntimeCoordinator ciRepair,
            JdbcTemplate jdbc,
            V2UserWaitStore userWaits,
            TaskPolicyRevisionRedriver policyRedriver)
    {
        return new V2TaskControlService(
                tasks, store, tickets, ciRepair, jdbc, userWaits,
                policyRedriver);
    }

    @Bean
    @ConditionalOnMissingBean(PlanningBaseTurnRuntime.class)
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
    public V2ThreadControlService v2ThreadControlService(
            PlanningBaseTurnRuntime planning,
            ThreadTurnProjection projection,
            DispatchTicketControl tickets,
            TrunkManager trunks,
            V2TrunkPurge purge,
            ThreadEngineOverrides engines,
            RoleRegistry roles,
            SessionKnowledgeProvider knowledge)
    {
        return new V2ThreadControlService(
                planning, projection, tickets, trunks, purge,
                engines, roles, knowledge);
    }

    @Bean
    @ConditionalOnMissingBean(ReviewAssignmentTurnRuntime.class)
    public ReviewAssignmentTurnRuntime v2ReviewAssignmentTurnRuntime(
            SqliteReviewAssignmentTurnStore store,
            ReviewProviderEndpoints providers,
            DispatchTicketControl tickets,
            ObjectMapper json,
            @Value("${server.port:8080}") int serverPort)
    {
        return new ReviewAssignmentTurnRuntime(
                store, providers, tickets::requestCancel,
                json, Clock.systemUTC(), serverPort);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public ExecutionDispatcher v2ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.DispatchWakeStore wakes,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            List<ExecutionPorts.MaintenanceWork> maintenanceWork,
            V2TaskCreationService taskCreation)
    {
        taskCreation.repairExistingTrunkEngineSnapshots();
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
