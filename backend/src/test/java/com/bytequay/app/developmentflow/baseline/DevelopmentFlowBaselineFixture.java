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
package com.bytequay.app.developmentflow.baseline;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.LocalReviewSubmission;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.RoundGateStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.localpr.PRService;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small real-store fixture for development-flow acceptance tests. It builds
 * current LEGACY rows with explicit owner links; it does not emulate the V2
 * workflow or introduce a second state-machine test framework.
 */
final class DevelopmentFlowBaselineFixture
{
    static final Instant NOW = Instant.parse("2026-07-28T09:00:00Z");

    private static final List<String> PUSH_EFFECTS =
            List.of("push_branch", "ensure_pull_request");
    private static final List<String> ROUND_EFFECTS =
            List.of("push_branch", "reply:remote-comment-1");

    private final ApplicationContext context;
    private final ThreadStore threads;
    private final TaskStore tasks;
    private final StageStore stages;
    private final ThreadTurnStore turns;
    private final PRService prs;
    private final LocalReviewSubmissionStore submissions;
    private final ValidationPassStore validations;
    private final ReviewRoundStore rounds;
    private final TaskPushStore pushes;
    private final RoundGateStore gates;

    private DevelopmentFlowBaselineFixture(ApplicationContext context)
    {
        this.context = context;
        threads = context.getBean(ThreadStore.class);
        tasks = context.getBean(TaskStore.class);
        stages = context.getBean(StageStore.class);
        turns = context.getBean(ThreadTurnStore.class);
        prs = context.getBean(PRService.class);
        submissions = context.getBean(LocalReviewSubmissionStore.class);
        validations = context.getBean(ValidationPassStore.class);
        rounds = context.getBean(ReviewRoundStore.class);
        pushes = context.getBean(TaskPushStore.class);
        gates = context.getBean(RoundGateStore.class);
    }

    static DevelopmentFlowBaselineFixture from(ApplicationContext context)
    {
        return new DevelopmentFlowBaselineFixture(context);
    }

    /** A fresh service object wired to the same stores and database. */
    <T> T recreateService(Class<T> serviceType)
    {
        return context.getAutowireCapableBeanFactory().createBean(serviceType);
    }

    PlanScenario createApprovedPlanCandidate(String scenario, Path repoRoot)
            throws IOException
    {
        Thread trunk = insertTrunk(scenario);
        Task task = insertTask(scenario, trunk, repoRoot, TaskStatus.RUNNING);
        tasks.updatePhase(task.id(), TaskPhase.PLANNING);

        StageInstance plan = stages.openStage(task.id(), StageType.PLAN_STAGE, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", scenario + "-plan-revision");
        payload.put("status", "finalized");
        payload.put("intent", Map.of(
                "summary", "exercise the persisted development-flow baseline",
                "steps", List.of(Map.of("ordinal", 1, "action", "apply the change")),
                "pushStrategy", "await_approval"));
        payload.put("signals", Map.of(
                "confidence", "high",
                "riskLevel", "low",
                "estimatedComplexity", "small"));
        stages.recordEvent(plan.id(), task.id(), StageEventType.PLAN_RECORDED, payload);
        stages.recordEvent(
                plan.id(), task.id(), StageEventType.PLAN_SELF_REVIEWED,
                Map.of(
                        "verdict", "approved",
                        "reviewedRevisionId", scenario + "-plan-revision"));

        return new PlanScenario(
                trunk,
                reloadTask(task.id()),
                plan,
                Path.of(task.worktreePath()));
    }

    OwnerGraph createRemoteOwnerGraph(String scenario, Path repoRoot)
            throws IOException
    {
        Thread trunk = insertTrunk(scenario);
        Task inserted = insertTask(scenario, trunk, repoRoot, TaskStatus.IN_REVIEW);
        String taskId = inserted.id();

        StageInstance plan = stages.openStage(taskId, StageType.PLAN_STAGE, null);
        stages.closeStage(plan.id(), "baseline_plan_approved");
        StageInstance local = stages.openStage(taskId, StageType.DEVELOPMENT_STAGE, null);
        stages.closeStage(local.id(), "baseline_remote_promoted");
        StageInstance remote = stages.openStage(taskId, StageType.REMOTE_DEVELOPMENT_STAGE, null);

        PR pr = prs.createForTask(
                taskId, inserted.branchName(), inserted.baseBranch(),
                "Baseline remote change", "Fixture-owned pull request");
        prs.recordCommit(pr.id(), scenario + "-sha", "Implement baseline", 3, 1, "agent");
        pr = prs.requestUserReview(pr.id(), "agent");
        pr = prs.recordPush(
                pr.id(), "acme/widget", 42,
                "https://github.com/acme/widget/pull/42");
        pr = prs.transition(pr.id(), PR.STATUS_REMOTE_OPEN, "github");
        prs.recordCheck(
                pr.id(), PRCheck.KIND_REMOTE, "build",
                PRCheck.STATUS_PASSED, 1000L);
        prs.recordRemoteReview(
                pr.id(), "reviewer", "changes_requested",
                "Please cover the edge case", NOW, 7001L);

        tasks.markPushed(taskId, NOW);
        tasks.linkPullRequest(taskId, 42, "open");
        tasks.linkTaskToPr(taskId, "acme/widget#42");
        tasks.updateCiState(taskId, "PASSING");
        tasks.updatePhase(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);

        String roundId = scenario + "-round";
        String runId = scenario + "-round-run";
        String gateToken = scenario + "-round-gate";
        ReviewRound round = rounds.insert(new ReviewRound(
                roundId,
                taskId,
                1,
                List.of("@reviewer"),
                ReviewRoundState.AWAITING_GATE,
                ReviewRound.ReviewRoundStats.empty(),
                runId,
                NOW,
                NOW,
                null,
                ReviewRound.ORIGIN_EXTERNAL,
                null,
                1,
                5,
                null,
                "sha256:" + scenario,
                0,
                0,
                0,
                gateToken,
                null));

        ThreadTurn trunkTurn = completedTurn(
                scenario + "-trunk-turn", trunk.id(), null, null,
                ThreadScope.TRUNK, null, "plan next task");
        ThreadTurn taskTurn = completedTurn(
                scenario + "-task-turn", trunk.id(), taskId, null,
                ThreadScope.TASK, null, "task brain review");
        ThreadTurn stageTurn = completedTurn(
                scenario + "-stage-turn", trunk.id(), taskId, remote.id().toString(),
                ThreadScope.STAGE, runId, "address remote review");
        turns.insertTurn(trunkTurn, false, scenario + ":trunk");
        turns.insertTurn(taskTurn, true, scenario + ":task");
        turns.insertTurn(stageTurn, false, scenario + ":stage");

        LocalReviewSubmission submission = new LocalReviewSubmission(
                scenario + "-local-batch",
                scenario + "-timeline-event",
                taskId,
                pr.id(),
                runId,
                1L,
                "[\"local-comment-1\"]",
                "[{\"id\":\"local-comment-1\",\"revision\":1}]",
                NOW,
                NOW,
                1,
                0,
                NOW,
                NOW,
                NOW,
                null,
                null);
        submissions.insert(submission);

        String claimKey = scenario + ":validation:" + roundId;
        long claimId = validations.insertClaim(
                        claimKey, taskId, "review-round", roundId,
                        "sha256:" + scenario, null, null, NOW)
                .orElseThrow();
        validations.finishPass(claimId, NOW, true, 0, "[]");
        ValidationClaim validation = validations.findByClaimKey(claimKey).orElseThrow();

        TaskPushAuthorization push = insertConsumedPushAuthorization(
                scenario, taskId, pr.id(), runId);
        RoundGateAuthorization gate = new RoundGateAuthorization(
                gateToken,
                taskId,
                roundId,
                0,
                1,
                Actor.HUMAN,
                "sha256:" + scenario,
                "{\"roundId\":\"" + roundId + "\"}",
                "sha256:" + scenario + ":round-payload",
                "[\"push_branch\",\"reply:remote-comment-1\"]",
                NOW,
                null,
                null,
                null);
        gates.insert(gate, ROUND_EFFECTS, 3);

        return new OwnerGraph(
                trunk,
                reloadTask(taskId),
                List.of(
                        stages.findStageById(plan.id()).orElseThrow(),
                        stages.findStageById(local.id()).orElseThrow(),
                        stages.findStageById(remote.id()).orElseThrow()),
                Path.of(inserted.worktreePath()),
                prs.findById(pr.id()).orElseThrow(),
                List.of(trunkTurn, taskTurn, stageTurn),
                submissions.findById(submission.id()).orElseThrow(),
                validation,
                rounds.findById(round.id()).orElseThrow(),
                push,
                pushes.findEffects(push.token()),
                gates.findAuthorization(gate.token()).orElseThrow(),
                gates.findEffects(gate.token()));
    }

    Task reloadTask(String taskId)
    {
        return tasks.findTaskById(taskId).orElseThrow();
    }

    StageInstance reloadStage(StageInstance stage)
    {
        return stages.findStageById(stage.id()).orElseThrow();
    }

    StageInstance activeStage(String taskId)
    {
        return stages.findActiveStage(taskId).orElseThrow();
    }

    private Thread insertTrunk(String scenario)
    {
        Thread trunk = new Thread(
                scenario + "-trunk",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Development flow baseline " + scenario,
                ThreadStatus.RUNNING,
                "claude-sonnet-4.6",
                0L,
                0L,
                0L,
                NOW,
                NOW,
                null,
                null,
                ThreadFlow.BUILD,
                "ws-default",
                null,
                null);
        threads.saveThread(trunk);
        return trunk;
    }

    private Task insertTask(
            String scenario, Thread trunk, Path repoRoot, TaskStatus status)
            throws IOException
    {
        Path repo = Files.createDirectories(repoRoot.resolve(scenario + "-repo"));
        Path worktree = Files.createDirectories(
                repo.resolve(".worktrees").resolve(scenario + "-task"));
        Task task = new Task(
                scenario + "-task",
                trunk.id(),
                1L,
                status,
                "codex/" + scenario,
                worktree.toString(),
                "main",
                repo.toString(),
                null,
                null,
                null,
                null,
                null,
                "DEVELOP",
                null,
                null,
                0L,
                0L,
                0L,
                null,
                NOW,
                null,
                null,
                "Baseline " + scenario,
                null,
                null,
                Task.ORIGIN_USER);
        tasks.insertTask(task);
        return reloadTask(task.id());
    }

    private ThreadTurn completedTurn(
            String id,
            String threadId,
            String taskId,
            String stageId,
            ThreadScope scope,
            String runId,
            String input)
    {
        return new ThreadTurn(
                id,
                threadId,
                taskId,
                ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED,
                input,
                NOW,
                NOW,
                NOW,
                NOW,
                null,
                scope == ThreadScope.TRUNK
                        ? TurnInitiator.user()
                        : TurnInitiator.unattended("baseline"),
                stageId,
                scope,
                runId);
    }

    private TaskPushAuthorization insertConsumedPushAuthorization(
            String scenario, String taskId, String prId, String runId)
    {
        String token = scenario + "-push";
        TaskPushAuthorization authorization = new TaskPushAuthorization(
                token,
                taskId,
                prId,
                runId,
                scenario + "-sha",
                "sha256:" + scenario,
                Actor.HUMAN,
                TaskPushAuthorization.BASIS_LEGACY_REMOTE,
                scenario + "-remote-fact",
                null,
                "{\"headSha\":\"" + scenario + "-sha\"}",
                "sha256:" + scenario + ":push-payload",
                "[\"push_branch\",\"ensure_pull_request\"]",
                NOW,
                null,
                null,
                null);
        pushes.insert(authorization, PUSH_EFFECTS, 3);
        for (String effect : PUSH_EFFECTS) {
            if (!pushes.completeObservedEffect(
                    token, effect,
                    "{\"effect\":\"" + effect + "\"}", NOW)) {
                throw new IllegalStateException("could not seed push effect " + effect);
            }
        }
        if (!pushes.consumeIfComplete(
                token, TaskPushAuthorization.OUTCOME_PUSHED, NOW)) {
            throw new IllegalStateException("could not seed consumed push authorization");
        }
        return pushes.findAuthorization(token).orElseThrow();
    }

    record PlanScenario(
            Thread trunk,
            Task task,
            StageInstance plan,
            Path worktree)
    {
    }

    record OwnerGraph(
            Thread trunk,
            Task task,
            List<StageInstance> stages,
            Path worktree,
            PR pr,
            List<ThreadTurn> turns,
            LocalReviewSubmission localReviewBatch,
            ValidationClaim validation,
            ReviewRound reviewRound,
            TaskPushAuthorization pushAuthorization,
            List<TaskPushEffect> pushEffects,
            RoundGateAuthorization roundAuthorization,
            List<RoundGateEffect> roundEffects)
    {
    }
}
