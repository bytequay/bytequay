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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.threads.AgentScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.baseline.DeterministicDevelopmentFlowFakes.deliver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TestDevelopmentFlowBaselineFixture
{
    @Autowired
    private ApplicationContext context;
    @Autowired
    private PlanStageService contextPlanService;
    @MockitoBean
    private AgentScheduler scheduler;

    @TempDir
    private Path tempDir;

    private DevelopmentFlowBaselineFixture fixture;

    @BeforeEach
    void setUp()
    {
        fixture = DevelopmentFlowBaselineFixture.from(context);
    }

    @Test
    void ownerGraphUsesRealStoresAndExactPersistedRelationships()
            throws Exception
    {
        DevelopmentFlowBaselineFixture.OwnerGraph graph =
                fixture.createRemoteOwnerGraph("owner-graph", tempDir);

        assertThat(graph.task().threadId()).isEqualTo(graph.trunk().id());
        assertThat(graph.task().worktreePath()).isEqualTo(graph.worktree().toString());
        assertThat(graph.worktree()).isDirectory();
        assertThat(graph.stages())
                .extracting(StageInstance::taskId)
                .containsOnly(graph.task().id());
        assertThat(graph.stages())
                .extracting(StageInstance::type)
                .containsExactly(
                        StageType.PLAN_STAGE,
                        StageType.DEVELOPMENT_STAGE,
                        StageType.REMOTE_DEVELOPMENT_STAGE);
        assertThat(graph.stages())
                .extracting(StageInstance::state)
                .containsExactly(StageState.CLOSED, StageState.CLOSED, StageState.OPEN);

        assertThat(graph.pr().taskId()).isEqualTo(graph.task().id());
        assertThat(graph.pr().status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
        assertThat(graph.pr().remotePrNumber()).isEqualTo(42);

        assertThat(graph.turns())
                .extracting(ThreadTurn::scope)
                .containsExactly(ThreadScope.TRUNK, ThreadScope.TASK, ThreadScope.STAGE);
        assertThat(graph.turns().get(0).taskId()).isNull();
        assertThat(graph.turns().get(1).taskId()).isEqualTo(graph.task().id());
        assertThat(graph.turns().get(1).stageId()).isNull();
        assertThat(graph.turns().get(2).taskId()).isEqualTo(graph.task().id());
        assertThat(graph.turns().get(2).stageId())
                .isEqualTo(graph.stages().get(2).id().toString());

        assertThat(graph.localReviewBatch().taskId()).isEqualTo(graph.task().id());
        assertThat(graph.localReviewBatch().prId()).isEqualTo(graph.pr().id());
        assertThat(graph.validation())
                .returns(graph.task().id(), ValidationClaim::taskId)
                .returns(graph.reviewRound().id(), ValidationClaim::roundId)
                .matches(ValidationClaim::isTerminalGreen);
        assertThat(graph.reviewRound().taskId()).isEqualTo(graph.task().id());
        assertThat(graph.reviewRound().status()).isEqualTo(ReviewRoundState.AWAITING_GATE);

        assertThat(graph.pushAuthorization().taskId()).isEqualTo(graph.task().id());
        assertThat(graph.pushAuthorization().prId()).isEqualTo(graph.pr().id());
        assertThat(graph.pushAuthorization().outcome())
                .isEqualTo(TaskPushAuthorization.OUTCOME_PUSHED);
        assertThat(graph.pushEffects()).allMatch(effect -> effect.completed());
        assertThat(graph.roundAuthorization().taskId()).isEqualTo(graph.task().id());
        assertThat(graph.roundAuthorization().roundId()).isEqualTo(graph.reviewRound().id());
        assertThat(graph.roundEffects())
                .extracting(RoundGateEffect::status)
                .containsOnly(RoundGateEffect.Status.PENDING);
    }

    @Test
    void recreatedServiceContinuesThePersistedPlanFlowAgainstTheSameDatabase()
            throws Exception
    {
        DevelopmentFlowBaselineFixture.PlanScenario scenario =
                fixture.createApprovedPlanCandidate("restart-plan", tempDir);
        PlanStageService recreated = fixture.recreateService(PlanStageService.class);

        assertThat(recreated).isNotSameAs(contextPlanService);
        PlanStageService.ApproveResult result = recreated.approveByStage(scenario.plan().id());

        assertThat(result.devStageId()).isNotBlank();
        assertThat(fixture.reloadStage(scenario.plan()).state()).isEqualTo(StageState.CLOSED);
        assertThat(fixture.reloadTask(scenario.task().id()).phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
        assertThat(fixture.activeStage(scenario.task().id()).type())
                .isEqualTo(StageType.DEVELOPMENT_STAGE);
        assertThat(scenario.worktree()).isDirectory();
    }

    @Test
    void scriptedResultsCanBeDeliveredOutOfOrderAndMoreThanOnce()
    {
        DeterministicDevelopmentFlowFakes.AgentExecutor agent =
                new DeterministicDevelopmentFlowFakes.AgentExecutor()
                        .script("operation-a", "stage-a", "result-a")
                        .script("operation-b", "stage-b", "result-b");
        List<DeterministicDevelopmentFlowFakes.AgentResult> completed = List.of(
                agent.execute("operation-a"), agent.execute("operation-b"));
        List<String> delivered = new ArrayList<>();

        deliver(completed, result -> delivered.add(result.operationId()), 1, 0, 1);

        assertThat(agent.executions()).containsExactly("operation-a", "operation-b");
        assertThat(delivered).containsExactly("operation-b", "operation-a", "operation-b");
    }

    @Test
    void ambiguousGitAndGitHubSuccessCanBeProbedWithoutRepeatingTheEffect()
            throws Exception
    {
        Path worktree = Files.createDirectories(tempDir.resolve("ambiguous-worktree"));
        DeterministicDevelopmentFlowFakes.GitAdapter git =
                new DeterministicDevelopmentFlowFakes.GitAdapter()
                        .head(worktree, "head-sha")
                        .ambiguousNextPush(worktree);

        assertThatThrownBy(() -> git.push(worktree))
                .isInstanceOf(
                        DeterministicDevelopmentFlowFakes.AmbiguousGitSuccessException.class);
        Optional<String> observedHead = git.remoteHeadSha(worktree, "origin", "feature");
        if (observedHead.isEmpty()) {
            git.push(worktree);
        }
        assertThat(observedHead).contains("head-sha");
        assertThat(git.pushCalls()).isEqualTo(1);

        RepoRef repo = RepoRef.of("acme", "widget");
        CreatePullRequestCommand command = CreatePullRequestCommand.draft(
                "alice:feature", "main", "Ambiguous create", "body");
        DeterministicDevelopmentFlowFakes.GitHubAdapter github =
                new DeterministicDevelopmentFlowFakes.GitHubAdapter()
                        .ambiguousNextCreate();

        assertThatThrownBy(() -> github.createPullRequest("pat", repo, command))
                .isInstanceOf(
                        DeterministicDevelopmentFlowFakes.AmbiguousGitHubSuccessException.class);
        List<?> observed = github.listPullRequests(
                "pat",
                repo,
                new ListPullRequestsQuery(
                        "open", Optional.of("alice:feature"), Optional.of("main"),
                        "created", "desc", 10, 1));

        assertThat(observed).hasSize(1);
        assertThat(github.createCalls()).isEqualTo(1);
    }
}
