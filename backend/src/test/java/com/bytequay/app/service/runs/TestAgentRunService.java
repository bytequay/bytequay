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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.StageStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentRunService
{
    private static final Instant NOW = Instant.parse("2026-07-05T00:00:00Z");
    private static final UUID BACKING_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final AgentRunStore store = mock(AgentRunStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final AgentRunService service =
            new AgentRunServiceImpl(store, stageStore, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void openStartsAFreshRunWithItsOwnBackingStage()
    {
        when(store.findLiveByTaskAndKind("t1", AgentRun.KIND_CI_FIX)).thenReturn(Optional.empty());
        when(stageStore.findStageByType("t1", StageType.CI_FIXING_STAGE)).thenReturn(Optional.empty());
        when(stageStore.openStage(eq("t1"), eq(StageType.CI_FIXING_STAGE), any()))
                .thenReturn(new StageInstance(
                        BACKING_STAGE_ID, "t1", StageType.CI_FIXING_STAGE, StageState.OPEN, NOW, null, null));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.open(
                "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, StageType.CI_FIXING_STAGE, 5);

        assertThat(run.taskId()).isEqualTo("t1");
        assertThat(run.kind()).isEqualTo(AgentRun.KIND_CI_FIX);
        assertThat(run.source()).isEqualTo(AgentRun.SOURCE_REMOTE);
        assertThat(run.stageId()).isEqualTo(BACKING_STAGE_ID.toString());
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(run.iterations()).isZero();
        assertThat(run.budget()).isEqualTo(5);
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.finishedAt()).isNull();
    }

    @Test
    void openDetachedCreatesAnArtifactRunOutsideTaskLifecycle()
    {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.openDetached(
                AgentRun.KIND_PANEL_REVIEW, null, "round-1", 50);

        assertThat(run.taskId()).isNull();
        assertThat(run.stageId()).isNull();
        assertThat(run.reviewRoundId()).isEqualTo("round-1");
        assertThat(run.kind()).isEqualTo(AgentRun.KIND_PANEL_REVIEW);
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(run.budget()).isEqualTo(50);
    }

    @Test
    void openTaskArtifactAttributesTheRunWithoutCreatingAStage()
    {
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.openTaskArtifact(
                "task-1", AgentRun.KIND_PANEL_REVIEW, null, "round-1", 50);

        assertThat(run.taskId()).isEqualTo("task-1");
        assertThat(run.stageId()).isNull();
        assertThat(run.parentStageId()).isNull();
        assertThat(run.reviewRoundId()).isEqualTo("round-1");
        assertThat(run.kind()).isEqualTo(AgentRun.KIND_PANEL_REVIEW);
        assertThat(run.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(run.budget()).isEqualTo(50);
        verify(stageStore, never()).openStage(any(), any(), any());
    }

    @Test
    void attachOwnershipPromotesTheSameArtifactRun()
    {
        AgentRun detached = new AgentRun(
                "run-review", null, AgentRun.KIND_PANEL_REVIEW, null,
                null, "round-1", null, AgentRun.STATUS_RUNNING,
                0, 50, null, null, NOW, null);
        when(store.findById(detached.id())).thenReturn(Optional.of(detached));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun owned = service.attachOwnership(
                detached.id(), "ws-1", "trunk-1",
                "anthropic", "claude-sonnet", "Review octocat/app#42");

        assertThat(owned.id()).isEqualTo(detached.id());
        assertThat(owned.workspaceId()).isEqualTo("ws-1");
        assertThat(owned.threadId()).isEqualTo("trunk-1");
        assertThat(owned.provider()).isEqualTo("anthropic");
        assertThat(owned.model()).isEqualTo("claude-sonnet");
        assertThat(owned.launchInput()).isEqualTo("Review octocat/app#42");
    }

    @Test
    void attachOwnershipAllowsAWorkspaceArtifactWithoutAThread()
    {
        AgentRun detached = new AgentRun(
                "run-review", null, AgentRun.KIND_PANEL_REVIEW, null,
                null, "round-1", null, AgentRun.STATUS_RUNNING,
                0, 50, null, null, NOW, null);
        when(store.findById(detached.id())).thenReturn(Optional.of(detached));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun owned = service.attachOwnership(
                detached.id(), "ws-1", null,
                "agent-review", "agent-review", "Review octocat/app#42");

        assertThat(owned.id()).isEqualTo(detached.id());
        assertThat(owned.workspaceId()).isEqualTo("ws-1");
        assertThat(owned.threadId()).isNull();
        assertThat(owned.provider()).isEqualTo("agent-review");
        assertThat(owned.model()).isEqualTo("agent-review");
        assertThat(owned.launchInput()).isEqualTo("Review octocat/app#42");
    }

    @Test
    void openIsIdempotentWhenALiveRunOfTheSameKindExists()
    {
        AgentRun existing = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 1, 5, null, null, NOW, null);
        when(store.findLiveByTaskAndKind("t1", AgentRun.KIND_CI_FIX)).thenReturn(Optional.of(existing));

        AgentRun run = service.open(
                "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, StageType.CI_FIXING_STAGE, 5);

        assertThat(run).isSameAs(existing);
        verify(stageStore, never()).openStage(any(), any(), any());
    }

    @Test
    void openNeverThreadsParentStageIdIntoTheBackingStagesCallerId()
    {
        // parentStageId still lands on the AgentRun record itself (whatever
        // the caller passes), but open() no longer forwards it as the backing
        // stage's caller id — every stage opens/reuses with a null caller.
        UUID devStageId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        when(store.findLiveByTaskAndKind("t1", AgentRun.KIND_CI_FIX)).thenReturn(Optional.empty());
        when(stageStore.findStageByType("t1", StageType.CI_FIXING_STAGE)).thenReturn(Optional.empty());
        when(stageStore.openStage(eq("t1"), eq(StageType.CI_FIXING_STAGE), isNull()))
                .thenReturn(new StageInstance(
                        BACKING_STAGE_ID, "t1", StageType.CI_FIXING_STAGE, StageState.OPEN, NOW, null, null));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.open(
                "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_LOCAL, devStageId.toString(),
                StageType.CI_FIXING_STAGE, null);

        assertThat(run.parentStageId()).isEqualTo(devStageId.toString());
        verify(stageStore).openStage("t1", StageType.CI_FIXING_STAGE, null);
    }

    @Test
    void openReopensAClosedStageOfTheSameTypeInsteadOfMintingANewOne()
    {
        StageInstance closedStage = new StageInstance(
                BACKING_STAGE_ID, "t1", StageType.CI_FIXING_STAGE, StageState.CLOSED, NOW, NOW, null);
        when(store.findLiveByTaskAndKind("t1", AgentRun.KIND_CI_FIX)).thenReturn(Optional.empty());
        when(stageStore.findStageByType("t1", StageType.CI_FIXING_STAGE)).thenReturn(Optional.of(closedStage));
        when(stageStore.reopenStage(BACKING_STAGE_ID)).thenReturn(new StageInstance(
                BACKING_STAGE_ID, "t1", StageType.CI_FIXING_STAGE, StageState.OPEN, NOW, null, null));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.open(
                "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, StageType.CI_FIXING_STAGE, 5);

        assertThat(run.stageId()).isEqualTo(BACKING_STAGE_ID.toString());
        verify(stageStore).reopenStage(BACKING_STAGE_ID);
        verify(stageStore, never()).openStage(any(), any(), any());
    }

    @Test
    void openReusesAnAlreadyOpenStageOfTheSameTypeWithoutReopeningOrCreating()
    {
        StageInstance openStage = new StageInstance(
                BACKING_STAGE_ID, "t1", StageType.CI_FIXING_STAGE, StageState.OPEN, NOW, null, null);
        when(store.findLiveByTaskAndKind("t1", AgentRun.KIND_CI_FIX)).thenReturn(Optional.empty());
        when(stageStore.findStageByType("t1", StageType.CI_FIXING_STAGE)).thenReturn(Optional.of(openStage));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun run = service.open(
                "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, StageType.CI_FIXING_STAGE, 5);

        assertThat(run.stageId()).isEqualTo(BACKING_STAGE_ID.toString());
        verify(stageStore, never()).reopenStage(any());
        verify(stageStore, never()).openStage(any(), any(), any());
    }

    @Test
    void recordIterationIncrementsAndOptionallyUpdatesTheHeadline()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 1, 5, "old", null, NOW, null);
        when(store.findById("run1")).thenReturn(Optional.of(run));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun updated = service.recordIteration("run1", "iter 2: retrying");

        assertThat(updated.iterations()).isEqualTo(2);
        assertThat(updated.headline()).isEqualTo("iter 2: retrying");
    }

    @Test
    void recordIterationKeepsTheExistingHeadlineWhenNoneIsSupplied()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 1, 5, "old", null, NOW, null);
        when(store.findById("run1")).thenReturn(Optional.of(run));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun updated = service.recordIteration("run1", null);

        assertThat(updated.iterations()).isEqualTo(2);
        assertThat(updated.headline()).isEqualTo("old");
    }

    @Test
    void spendBudgetDecrementsAndFloorsAtZero()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, 0, null, null, NOW, null);
        when(store.findById("run1")).thenReturn(Optional.of(run));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun updated = service.spendBudget("run1");

        assertThat(updated.budget()).isZero();
    }

    @Test
    void transitionToATerminalStatusClosesTheBackingStage()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 3, 2, null, null, NOW, null);
        when(store.findById("run1")).thenReturn(Optional.of(run));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun updated = service.transition("run1", AgentRun.STATUS_SUCCEEDED, "checks_green");

        assertThat(updated.status()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(updated.finishedAt()).isEqualTo(NOW);
        verify(stageStore).closeStage(BACKING_STAGE_ID, "checks_green");
    }

    @Test
    void transitionToAwaitingGateDoesNotCloseTheBackingStage()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_REVIEW_ROUND, null, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(store.findById("run1")).thenReturn(Optional.of(run));
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun updated = service.transition("run1", AgentRun.STATUS_AWAITING_GATE, "drafts_ready");

        assertThat(updated.finishedAt()).isNull();
        verify(stageStore, never()).closeStage(any(), any());
    }

    @Test
    void terminalRunCannotBeUncancelledByALateWorker()
    {
        AgentRun cancelled = new AgentRun(
                "run1", null, AgentRun.KIND_PANEL_REVIEW, null, null, "round-1",
                null, AgentRun.STATUS_CANCELLED, 0, 50, null, null, NOW, NOW);
        when(store.findById("run1")).thenReturn(Optional.of(cancelled));

        AgentRun unchanged = service.transition(
                "run1", AgentRun.STATUS_SUCCEEDED, "late completion");

        assertThat(unchanged).isSameAs(cancelled);
        verify(store, never()).save(any());
    }

    @Test
    void liveRunsByTaskDelegatesToTheStore()
    {
        AgentRun run = new AgentRun(
                "run1", "t1", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                BACKING_STAGE_ID.toString(), AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(store.findLiveByTask("t1")).thenReturn(List.of(run));

        assertThat(service.liveRunsByTask("t1")).containsExactly(run);
    }

    @Test
    void reviewRoundRunsDelegateToTheStore()
    {
        AgentRun run = new AgentRun(
                "run1", null, AgentRun.KIND_PANEL_REVIEW, null, null, "round-1",
                null, AgentRun.STATUS_RUNNING, 0, 50, null, null, NOW, null);
        when(store.findByReviewRound("round-1")).thenReturn(List.of(run));

        assertThat(service.findByReviewRound("round-1")).containsExactly(run);
    }
}
