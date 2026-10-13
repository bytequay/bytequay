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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDevelopmentResultBridgeResolverImpl
{
    private static final DevelopmentAutomation AUTOMATION = new DevelopmentAutomation(true, true, true, Optional.empty());
    private static final DevelopmentAutomation LOCAL_ONLY = new DevelopmentAutomation(false, false, false, Optional.empty());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final DevelopmentBranchService branches = mock(DevelopmentBranchService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final DevelopmentResultBridgeResolverImpl resolver = new DevelopmentResultBridgeResolverImpl(tasks, turns, reviews,
            beans.getBeanProvider(DevelopmentBranchService.class));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("branches", branches);
    }

    @Test
    void bindsTheAcceptedTurnEvenWhenTaskDefaultsHaveChanged()
    {
        AgentTask task = task(true, Optional.of(LOCAL_ONLY));
        AgentTurn turn = accept(task, Optional.of(AUTOMATION));

        DevelopmentResultBridge binding = resolver.resolve(task.id().toString()).orElseThrow();
        assertThat(binding.turnId()).isEqualTo(turn.id().toString());
        assertThat(binding.instruction()).contains("readyForDelivery=false", "root agent", "rename_task_branch");
        when(branches.rename(task.id(), turn.id(), "convert-parquet-time-millis-to-micro")).thenReturn("user/bytequay/convert-parquet-time-millis-to-micro");
        assertThat(binding.branchRenamer().orElseThrow().apply("convert-parquet-time-millis-to-micro")).isEqualTo("user/bytequay/convert-parquet-time-millis-to-micro");

        AgentTurn next = accept(task, Optional.of(AUTOMATION));
        DevelopmentResultBridge nextBinding = resolver.resolve(task.id().toString()).orElseThrow();
        assertThat(nextBinding.turnId()).isEqualTo(next.id().toString());
        assertThat(nextBinding.branchRenamer()).isEmpty();
        assertThat(nextBinding.instruction()).doesNotContain("rename_task_branch").contains("Keep the task branch name fixed after the first turn");
    }

    @Test
    void laterTurnsCannotAcquireBranchNamingWhenTheFirstTurnDidNotRename()
    {
        AgentTask task = task(true, Optional.of(AUTOMATION));
        accept(task, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().branchRenamer()).isPresent();

        accept(task, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().branchRenamer()).isEmpty();

        accept(task, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().branchRenamer()).isEmpty();
    }

    @Test
    void localOnlyAndOrdinaryTurnsDoNotRequireAReport()
    {
        AgentTask task = task(true, Optional.of(AUTOMATION));
        accept(task, Optional.of(LOCAL_ONLY));
        assertThat(resolver.resolve(task.id().toString())).isEmpty();

        accept(task, Optional.empty());
        assertThat(resolver.resolve(task.id().toString())).isEmpty();
    }

    @Test
    void summaryRefinementAloneCanReportLearningWithAutomationOff()
    {
        AgentTask task = task(true, Optional.of(LOCAL_ONLY));
        AgentTurn source = accept(task, Optional.of(LOCAL_ONLY));
        AgentTurn refinement = accept(task, Optional.of(LOCAL_ONLY))
                .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, source.id()));
        when(turns.findActive(task.id())).thenReturn(Optional.of(refinement));
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().learningSummaryAllowed()).isTrue();
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().branchRenamer()).isEmpty();
        accept(task, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(task.id().toString()).orElseThrow().learningSummaryAllowed()).isFalse();
    }

    @Test
    void workflowAndReviewOwnedTasksCannotAcquireTheDevelopmentTool()
    {
        AgentTask workflow = task(false, Optional.of(AUTOMATION));
        accept(workflow, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(workflow.id().toString())).isEmpty();

        AgentTask review = task(true, Optional.of(AUTOMATION));
        accept(review, Optional.of(AUTOMATION));
        when(reviews.findReviewByTask(review.id().toString())).thenReturn(Optional.of(new AgentReview(
                "review", "workspace", "bytequay/sample", OptionalInt.of(1), OptionalLong.empty(), review.id().toString(), Optional.empty(), AgentReview.State.OPEN,
                false, Optional.empty(), Optional.empty(), Optional.empty(), 0, review.createdAt(), review.createdAt(), Optional.empty())));
        assertThat(resolver.resolve(review.id().toString())).isEmpty();
    }

    @Test
    void missingIdleAndFailedTasksHaveNoReportingBinding()
    {
        assertThat(resolver.resolve(TaskId.random().toString())).isEmpty();

        AgentTask task = task(true, Optional.of(AUTOMATION));
        assertThat(resolver.resolve(task.id().toString())).isEmpty();

        accept(task, Optional.of(AUTOMATION));
        Instant failedAt = task.createdAt().plusSeconds(1);
        when(tasks.find(task.id())).thenReturn(Optional.of(task.fail("Failed", failedAt, failedAt.plus(Duration.ofDays(7)))));
        assertThat(resolver.resolve(task.id().toString())).isEmpty();
    }

    private AgentTask task(boolean interactive, Optional<DevelopmentAutomation> automation)
    {
        AgentTask task = AgentTask.created(
                TaskId.random(), Optional.of("workspace"), interactive, "Development", new AgentLaneId("codex"),
                new RepositoryIdentity("bytequay", "sample"), Path.of("/tmp/development-worktree"),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty(), automation), Instant.now());
        when(tasks.find(task.id())).thenReturn(Optional.of(task));
        return task;
    }

    private AgentTurn accept(AgentTask task, Optional<DevelopmentAutomation> automation)
    {
        AgentTurn turn = AgentTurn.accepted(
                TurnId.random(), task.id(), "request", new AgentTurnSettings(
                        Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofHours(1),
                        ImmutableList.of(), Optional.empty(), automation), task.createdAt());
        ImmutableList<AgentTurn> history = ImmutableList.<AgentTurn>builder().addAll(turns.list(task.id())).add(turn).build();
        when(turns.list(task.id())).thenReturn(history);
        when(turns.findActive(task.id())).thenReturn(Optional.of(turn));
        return turn;
    }
}
